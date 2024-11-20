package com.epam.reportportal.extension.importing.model;

import com.epam.ta.reportportal.ws.reporting.ItemAttributesRQ;
import java.time.Instant;
import java.util.Set;

public class ItemInfo {

  private String uuid;
  private String description;
  private Set<ItemAttributesRQ> itemAttributes;

  private Instant startTime;

  private long duration;

  public String getUuid() {
    return uuid;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  public Set<ItemAttributesRQ> getItemAttributes() {
    return itemAttributes;
  }

  public void setItemAttributes(
      Set<ItemAttributesRQ> itemAttributes) {
    this.itemAttributes = itemAttributes;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public void setStartTime(Instant startTime) {
    this.startTime = startTime;
  }

  public long getDuration() {
    return duration;
  }

  public void setDuration(long duration) {
    this.duration = duration;
  }
}
