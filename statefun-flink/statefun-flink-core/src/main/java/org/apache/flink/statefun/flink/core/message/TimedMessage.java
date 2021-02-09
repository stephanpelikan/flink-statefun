package org.apache.flink.statefun.flink.core.message;

public class TimedMessage {

  private long timestamp;
  private Message message;

  public TimedMessage() {}

  public TimedMessage(long timestamp, Message message) {
    this.timestamp = timestamp;
    this.message = message;
  }

  public Message getMessage() {
    return message;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public void setMessage(Message message) {
    this.message = message;
  }
}
