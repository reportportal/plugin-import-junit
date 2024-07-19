package com.epam.reportportal.extension.importing.model;

import com.epam.ta.reportportal.ws.reporting.ItemAttributesRQ;
import java.util.Set;

public class ItemInfo {

  private String uuid;
  private String description;
  private Set<ItemAttributesRQ> itemAttributes;

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
}
