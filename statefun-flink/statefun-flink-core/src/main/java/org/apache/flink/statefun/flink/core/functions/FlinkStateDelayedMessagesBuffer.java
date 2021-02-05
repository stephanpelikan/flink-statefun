/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.statefun.flink.core.functions;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections.Transformer;
import org.apache.commons.collections.iterators.TransformIterator;
import org.apache.flink.runtime.state.internal.InternalListState;
import org.apache.flink.runtime.state.internal.InternalMapState;
import org.apache.flink.statefun.flink.core.di.Inject;
import org.apache.flink.statefun.flink.core.di.Label;
import org.apache.flink.statefun.flink.core.message.Message;
import org.apache.flink.statefun.flink.core.message.TimedMessage;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedGenerator;

final class FlinkStateDelayedMessagesBuffer implements DelayedMessagesBuffer {

  static final String BUFFER_STATE_NAME = "delayed-messages-buffer";

  static final String BUFFER_MESSAGES_STATE_NAME = "delayed-timedmessages-buffer";

  private static final TimeBasedGenerator uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface());

  /**
   * Since FLINK-XXXXX: holds two kind of objects
   * - Message: delayed messages triggered and stored in checkpoint/savepoint
   *   before upgrading to a version implementing FLINK-XXXXX and restored by the new version
   * - String: ids of messages stored in 'bufferedMessages'
   */
  private final InternalListState<String, Long, Object> bufferState;
  
  /**
   * Stores the message and the timestamp of a delayed message by a message-key.
   */
  private final InternalMapState<String, String, String, TimedMessage> bufferedMessages;
  
  @Inject
  FlinkStateDelayedMessagesBuffer(
      @Label(DelayedMessagesBuffer.BUFFER_STATE_LABEL)
          InternalListState<String, Long, Object> bufferState,
      @Label(DelayedMessagesBuffer.BUFFER_MESSAGES_LABEL)
          InternalMapState<String, String, String, TimedMessage> bufferedMessages) {
    this.bufferState = Objects.requireNonNull(bufferState);
    this.bufferedMessages = Objects.requireNonNull(bufferedMessages);
  }

  @Override
  public String add(Message message, long untilTimestamp) {
    String messageId = uuidGenerator.generate().toString();
    try {
      bufferedMessages.setCurrentNamespace(getLocationPartOfMessageId(messageId));
      bufferedMessages.put(getNonLocatinPartOfMessageId(messageId), new TimedMessage(untilTimestamp, message));
      bufferState.setCurrentNamespace(untilTimestamp);
      bufferState.add(messageId);
    } catch (Exception e) {
      throw new RuntimeException("Error adding delayed message to state buffer: " + message, e);
    }
    return messageId;
  }
  
  @Override
  public Long remove(String messageId) {
    try {
      bufferedMessages.setCurrentNamespace(getLocationPartOfMessageId(messageId));
      String key = getNonLocatinPartOfMessageId(messageId);
      TimedMessage timedMessage = bufferedMessages.get(key);
      bufferedMessages.remove(key);
      bufferState.setCurrentNamespace(timedMessage.getTimestamp());
      List<Object> newState = updatedListOfMessageReferences(messageId, bufferState.get());
      if (newState.isEmpty()) {
        bufferState.clear();
      } else {
        bufferState.updateInternal(newState);
      }
      return newState.isEmpty() ? timedMessage.getTimestamp() : null;
    } catch (Exception e) {
      throw new RuntimeException(
          "Error clearing buffered messages for '" + messageId + "'", e);
    }
  }
  
  private List<Object> updatedListOfMessageReferences(String messageId, Iterable<Object> currentState) {
    final List<Object> result = new LinkedList<>();
    currentState.forEach(state -> {
      if (!state.equals(messageId)) {
        result.add(state);
      }
    });
    return result;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterable<Message> getForTimestamp(long timestamp) {
    bufferState.setCurrentNamespace(timestamp);
    try {
      final Iterable<Object> timedMessages = bufferState.get();
      if (timedMessages == null) {
          return null;
      }
      return (Iterable<Message>) new TransformIterator(timedMessages.iterator(), new Transformer() {
        @Override
        public Message transform(Object input) {
          if (input instanceof Message) {
            return (Message) input;
          }
          try {
            return bufferedMessages.get(getNonLocatinPartOfMessageId(input.toString())).getMessage();
          } catch (Exception e) {
            throw new RuntimeException(
                 "Error accessing delayed message in buffered messages for timestamp: " + timestamp, e);
          }
        }
      });
    } catch (Exception e) {
      throw new RuntimeException(
          "Error accessing delayed message in state buffer for timestamp: " + timestamp, e);
    }
  }

  @Override
  public void clearForTimestamp(long timestamp) {
    bufferState.setCurrentNamespace(timestamp);
    clearBufferedMessages(timestamp);
    bufferState.clear();
  }
  
  private void clearBufferedMessages(long timestamp) {
    try {
      Iterable<Object> timedMessages = bufferState.get();
      if (timedMessages == null) {
        return;
      }
      timedMessages.forEach(state -> {
        if (state instanceof Message) {
          return;
        }
        try {
          String messageId = state.toString();
          bufferedMessages.setCurrentNamespace(getLocationPartOfMessageId(messageId));
          bufferedMessages.remove(getNonLocatinPartOfMessageId(messageId));
        } catch (Exception e) {
          throw new RuntimeException(
               "Error clearing buffered message '" + state + "' for timestamp: " + timestamp, e);
        }
      });
    } catch (Exception e) {
      throw new RuntimeException(
           "Error clearing buffered messages for timestamp: " + timestamp, e);
    }
  }
  
  /**
   * For scoping the total amount of stored messages the location-part
   * of the id (last 12 digits e.g. 'e3e21425-652d-11eb-bca5-1230362d1657' ->
   * '1230362d1657') is used as namespace.
   * 
   * @param messageId The message id
   * @return The location-based part of the UUID
   */
  private String getLocationPartOfMessageId(String messageId) {
    return messageId.substring(messageId.lastIndexOf('-') + 1);
  }
  
  private String getNonLocatinPartOfMessageId(String messageId) {
    return messageId.substring(0, messageId.lastIndexOf('-'));
  }
}
