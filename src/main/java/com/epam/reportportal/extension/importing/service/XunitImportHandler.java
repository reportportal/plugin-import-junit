/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.reportportal.extension.importing.service;

import static com.epam.reportportal.extension.importing.service.XunitReportTag.ATTR_NAME;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.ATTR_TIME;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.START_TIME;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.TESTSUITE;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.TESTSUITES;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.TIMESTAMP;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.fromString;
import static com.epam.reportportal.extension.importing.utils.DateUtils.toMillis;
import static com.epam.reportportal.infrastructure.persistence.entity.enums.TestItemIssueGroup.NOT_ISSUE_FLAG;
import static java.util.Optional.ofNullable;

import com.epam.reportportal.extension.importing.model.ItemInfo;
import com.epam.reportportal.infrastructure.events.FinishItemRqEvent;
import com.epam.reportportal.infrastructure.events.SaveLogRqEvent;
import com.epam.reportportal.infrastructure.events.StartChildItemRqEvent;
import com.epam.reportportal.infrastructure.events.StartRootItemRqEvent;
import com.epam.reportportal.infrastructure.persistence.entity.enums.LogLevel;
import com.epam.reportportal.infrastructure.persistence.entity.enums.StatusEnum;
import com.epam.reportportal.infrastructure.persistence.entity.enums.TestItemTypeEnum;
import com.epam.reportportal.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.reporting.FinishTestItemRQ;
import com.epam.reportportal.reporting.Issue;
import com.epam.reportportal.reporting.ItemAttributesRQ;
import com.epam.reportportal.reporting.SaveLogRQ;
import com.epam.reportportal.reporting.StartTestItemRQ;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

public class XunitImportHandler extends DefaultHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(XunitImportHandler.class);
  private static final int MAX_ENTITY_NAME_LENGTH = 256;
  private final ApplicationEventPublisher eventPublisher;
  private String projectName;
  private String launchUuid;
  private boolean isSkippedNotIssue = false;
  private long commonDuration;
  private Deque<ItemInfo> itemInfos;
  private StatusEnum status;
  private StringBuilder message;
  private boolean rootVerified;
  private Instant lowestTime = Instant.now();

  private Instant currentTime;

  public XunitImportHandler(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void startDocument() {
    itemInfos = new ArrayDeque<>();
    message = new StringBuilder();
  }

  @Override
  public void endDocument() {
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    verifyRootElement(qName);
    switch (XunitReportTag.fromString(qName)) {
      case TESTSUITE:
        if (itemInfos.isEmpty()) {
          startRootItem(attributes);
        } else {
          startTestItem(attributes);
        }
        break;
      case TESTCASE:
        startStepItem(attributes);
        break;
      case ERROR:
      case FAILURE:
        message = new StringBuilder();
        status = StatusEnum.FAILED;
        break;
      case SKIPPED:
        message = new StringBuilder();
        status = StatusEnum.SKIPPED;
        break;
      case SYSTEM_OUT:
      case SYSTEM_ERR:
      case WARNING:
        message = new StringBuilder();
        break;
      case PROPERTIES:
        if (itemInfos.peek() != null) {
          itemInfos.peek().setItemAttributes(new HashSet<>());
        }
        break;
      case PROPERTY:
        handleProperty(attributes);
      case UNKNOWN:
      default:
        LOGGER.debug("Unknown tag: {}", qName);
        break;
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    switch (XunitReportTag.fromString(qName)) {
      case TESTSUITE:
        finishRootItem();
        break;
      case TESTCASE:
        finishTestItem();
        break;
      case SKIPPED:
      case ERROR:
      case FAILURE:
      case SYSTEM_ERR:
        attachLog(LogLevel.ERROR);
        break;
      case SYSTEM_OUT:
        attachLog(LogLevel.INFO);
        break;
      case WARNING:
        attachLog(LogLevel.WARN);
        break;
      case PROPERTIES:
        pushDescription();
      case UNKNOWN:
      default:
        LOGGER.debug("Unknown tag: {}", qName);
        break;
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    String msg = new String(ch, start, length);
    if (!msg.isEmpty()) {
      message.append(msg);
    }
  }

  private void verifyRootElement(String qName) {
    if (!rootVerified) {
      XunitReportTag rootTag = fromString(qName);
      if (!Lists.newArrayList(TESTSUITES, TESTSUITE).contains(rootTag)) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "Root node in junit xml file must be 'testsuites' or 'testsuite'");
      }
      rootVerified = true;
    }
  }

  private void startRootItem(Attributes attributes) {
    Instant time = ofNullable(resolveStartTime(attributes)).orElse(Instant.now());
    currentTime = time;
    var rq = buildStartTestRq(attributes.getValue(ATTR_NAME.getValue()), time);
    eventPublisher.publishEvent(new StartRootItemRqEvent(projectName, rq));

    var itemInfo = new ItemInfo();
    itemInfo.setUuid(rq.getUuid());
    itemInfo.setStartTime(time);
    itemInfo.setDuration(toMillis(attributes.getValue(ATTR_TIME.getValue())));
    itemInfos.push(itemInfo);

    if (time.isBefore(lowestTime)) {
      lowestTime = time;
    }
  }

  private void startTestItem(Attributes attributes) {
    Instant time = ofNullable(resolveStartTime(attributes)).orElse(itemInfos.peek().getStartTime());
    currentTime = time;
    StartTestItemRQ rq = buildStartTestRq(
        StringUtils.abbreviate(attributes.getValue(ATTR_NAME.getValue()), MAX_ENTITY_NAME_LENGTH),
        time);
    if (itemInfos.peek() != null) {
      eventPublisher.publishEvent(
          new StartChildItemRqEvent(projectName, itemInfos.peek().getUuid(), rq));
    }
    var itemInfo = new ItemInfo();
    itemInfo.setUuid(rq.getUuid());
    itemInfo.setStartTime(time);
    itemInfo.setDuration(toMillis(attributes.getValue(ATTR_TIME.getValue())));
    itemInfos.push(itemInfo);
  }

  private void startStepItem(Attributes attributes) {
    var time = ofNullable(resolveStartTime(attributes)).orElse(currentTime);
    var rq = new StartTestItemRQ();
    rq.setUuid(UUID.randomUUID().toString());
    rq.setLaunchUuid(launchUuid);
    rq.setType(TestItemTypeEnum.STEP.name());
    rq.setName(
        StringUtils.abbreviate(attributes.getValue(ATTR_NAME.getValue()), MAX_ENTITY_NAME_LENGTH));
    rq.setStartTime(time);

    eventPublisher.publishEvent(
        new StartChildItemRqEvent(projectName, itemInfos.peek().getUuid(), rq));

    var itemInfo = new ItemInfo();
    itemInfo.setUuid(rq.getUuid());
    itemInfo.setStartTime(time);
    itemInfo.setDuration(toMillis(attributes.getValue(ATTR_TIME.getValue())));
    itemInfos.push(itemInfo);
  }

  private Instant resolveStartTime(Attributes attributes) {
    Instant time = null;
    if (StringUtils.isNotEmpty(attributes.getValue(START_TIME.getValue()))) {
      time = parseTimeStamp(attributes.getValue(START_TIME.getValue()));
    } else if (StringUtils.isNotEmpty(attributes.getValue(TIMESTAMP.getValue()))) {
      time = parseTimeStamp(attributes.getValue(TIMESTAMP.getValue()));
    }
    return time;
  }

  private void finishRootItem() {
    var itemInfo = itemInfos.poll();
    if (itemInfo == null) {
      return;
    }
    var rq = new FinishTestItemRQ();
    markAsNotIssue(rq);
    rq.setLaunchUuid(launchUuid);
    rq.setEndTime(itemInfo.getStartTime().plus(itemInfo.getDuration(), ChronoUnit.MILLIS));
    rq.setAttributes(itemInfo.getItemAttributes());
    rq.setDescription(itemInfo.getDescription());

    eventPublisher.publishEvent(
        new FinishItemRqEvent(projectName, itemInfo.getUuid(), rq));

    status = null;
    currentTime = null;
  }

  private void finishTestItem() {
    var itemInfo = itemInfos.poll();
    if (itemInfo == null) {
      return;
    }

    Instant endTime = itemInfo.getStartTime().plus(itemInfo.getDuration(), ChronoUnit.MILLIS);
    commonDuration += itemInfo.getDuration();

    var rq = new FinishTestItemRQ();
    markAsNotIssue(rq);
    rq.setLaunchUuid(launchUuid);
    rq.setEndTime(endTime);
    rq.setStatus(ofNullable(status).orElse(StatusEnum.PASSED).name());
    rq.setAttributes(itemInfo.getItemAttributes());
    rq.setDescription(itemInfo.getDescription());

    eventPublisher.publishEvent(
        new FinishItemRqEvent(projectName, itemInfo.getUuid(), rq));

    status = null;
    currentTime = endTime;
  }

  private void attachLog(LogLevel logLevel) {
    if (null != message && message.length() != 0) {
      var saveLogRQ = new SaveLogRQ();
      saveLogRQ.setLaunchUuid(launchUuid);
      saveLogRQ.setLevel(logLevel.name());
      saveLogRQ.setLogTime(itemInfos.peek().getStartTime());
      saveLogRQ.setMessage(message.toString().trim());
      saveLogRQ.setItemUuid(itemInfos.peek().getUuid());
      eventPublisher.publishEvent(new SaveLogRqEvent(projectName, saveLogRQ, null));
      message = new StringBuilder();
    }
  }

  private StartTestItemRQ buildStartTestRq(String name, Instant startTime) {
    var rq = new StartTestItemRQ();
    rq.setUuid(UUID.randomUUID().toString());
    rq.setLaunchUuid(launchUuid);
    rq.setStartTime(startTime);
    rq.setType(TestItemTypeEnum.SUITE.name());
    rq.setName(Strings.isNullOrEmpty(name) ? "no_name" : name);
    return rq;
  }

  private void handleProperty(Attributes attributes) {
    var itemInfo = itemInfos.peek();
    if (itemInfo == null) {
      return;
    }
    if ("attribute".equalsIgnoreCase(attributes.getValue("name"))) {
      var value = Arrays.stream(attributes.getValue("value").split(":"))
          .filter(it -> !StringUtils.isEmpty(it)).toArray(String[]::new);
      if (value.length > 1) {
        itemInfo.getItemAttributes().add(new ItemAttributesRQ(value[0], value[1]));
      } else {
        itemInfo.getItemAttributes().add(new ItemAttributesRQ(value[0]));
      }
    }
  }

  private void markAsNotIssue(FinishTestItemRQ rq) {
    if (StatusEnum.SKIPPED.equals(status) && isSkippedNotIssue) {
      var issue = new Issue();
      issue.setIssueType(NOT_ISSUE_FLAG.getValue());
      rq.setIssue(issue);
    }
  }

  private void pushDescription() {
    if (!StringUtils.isEmpty(message) && itemInfos.peek() != null) {
      itemInfos.peek().setDescription(message.toString());
      message = new StringBuilder();
    }
  }

  public void withParameters(String launchId, String projectName, boolean isSkippedNotIssue) {
    this.projectName = projectName;
    this.launchUuid = launchId;
    this.isSkippedNotIssue = isSkippedNotIssue;
  }

  public Instant getLowestTime() {
    return lowestTime;
  }

  public long getCommonDuration() {
    return commonDuration;
  }

  private Instant parseTimeStamp(String timestamp) {
    // try to parse datetime as Long, otherwise parse as timestamp
    try {
      return Instant.ofEpochMilli(Long.parseLong(timestamp));
    } catch (NumberFormatException ignored) {
      DateTimeFormatter formatter =
          new DateTimeFormatterBuilder().appendOptional(DateTimeFormatter.RFC_1123_DATE_TIME)
              .appendOptional(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
              .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE_TIME).optionalStart()
              .appendOffsetId().appendZoneId().optionalEnd().optionalStart().appendLiteral(' ')
              .parseCaseSensitive().appendZoneId().optionalEnd().toFormatter();

      TemporalAccessor temporalAccessor = formatter.parse(timestamp);
      if (isParsedTimeStampHasOffset(temporalAccessor)) {
        return ZonedDateTime.from(temporalAccessor).withZoneSameInstant(ZoneOffset.UTC)
            .toInstant();
      } else {
        return LocalDateTime.from(temporalAccessor).toInstant(ZoneOffset.UTC);
      }
    }

  }

  private boolean isParsedTimeStampHasOffset(TemporalAccessor temporalAccessor) {
    return temporalAccessor.query(TemporalQueries.offset()) != null;
  }

}
