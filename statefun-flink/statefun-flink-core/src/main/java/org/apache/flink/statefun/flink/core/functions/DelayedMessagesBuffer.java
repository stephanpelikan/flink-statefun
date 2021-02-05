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

import org.apache.flink.statefun.flink.core.message.Message;

interface DelayedMessagesBuffer {

  String BUFFER_LABEL = "delayed-messages-buffer";
  
  String BUFFER_STATE_LABEL = "delayed-messages-buffer-state";

  String BUFFER_MESSAGES_LABEL = "delayed-timedmessages-buffer-state";

  /**
   * @param message The message
   * @param untilTimestamp The delay of delivery
   * @return The message id
   */
  String add(Message message, long untilTimestamp);
  
  /**
   * @param messageId The message id to remove
   * @return the timestamp of the message, if no further message is registered for that timestamp otherwise null
   */
  Long remove(String messageId);

  Iterable<Message> getForTimestamp(long timestamp);

  void clearForTimestamp(long timestamp);
}
