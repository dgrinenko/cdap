/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.internal.app.runtime.monitor;

import com.carrotsearch.hppc.AbstractIterator;
import com.google.inject.Inject;
import io.cdap.cdap.api.dataset.lib.CloseableIterator;
import io.cdap.cdap.api.messaging.Message;
import io.cdap.cdap.api.messaging.MessagingContext;
import io.cdap.cdap.api.messaging.TopicNotFoundException;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.logging.LogSamplers;
import io.cdap.cdap.common.logging.Loggers;
import io.cdap.cdap.common.service.AbstractRetryableScheduledService;
import io.cdap.cdap.common.service.RetryStrategies;
import io.cdap.cdap.messaging.MessagingService;
import io.cdap.cdap.messaging.context.MultiThreadMessagingContext;
import io.cdap.cdap.proto.id.NamespaceId;
import io.cdap.cdap.proto.id.ProgramRunId;
import io.cdap.cdap.proto.id.TopicId;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A service that periodically relay messages from local TMS to the runtime server.
 * This service runs in the remote runtime.
 */
public class RuntimeClientService extends AbstractRetryableScheduledService {

  private static final Logger LOG = LoggerFactory.getLogger(RuntimeClientService.class);
  private static final Logger PROGRESS_LOG = Loggers.sampling(LOG,
                                                              LogSamplers.limitRate(TimeUnit.SECONDS.toMillis(30)));

  private final Map<String, TopicRelayer> topicRelayers;
  private final MessagingContext messagingContext;
  private final long pollTimeMillis;
  private final ProgramRunId programRunId;
  private final RuntimeClient runtimeClient;
  private final int fetchLimit;

  @Inject
  RuntimeClientService(CConfiguration cConf, MessagingService messagingService,
                       DiscoveryServiceClient discoveryServiceClient, ProgramRunId programRunId) {
    super(RetryStrategies.fromConfiguration(cConf, "system.runtime.monitor."));
    this.topicRelayers = RuntimeMonitors.createTopicConfigs(cConf).entrySet().stream()
      .collect(Collectors.toMap(Map.Entry::getKey, e -> new TopicRelayer(NamespaceId.SYSTEM.topic(e.getValue()))));
    this.messagingContext = new MultiThreadMessagingContext(messagingService);
    this.pollTimeMillis = cConf.getLong(Constants.RuntimeMonitor.POLL_TIME_MS);
    this.programRunId = programRunId;
    this.runtimeClient = new RuntimeClient(discoveryServiceClient);
    this.fetchLimit = cConf.getInt(Constants.RuntimeMonitor.BATCH_SIZE);
  }

  @Override
  protected long runTask() throws Exception {
    long nextPollDelay = pollTimeMillis;
    for (Map.Entry<String, TopicRelayer> entry : topicRelayers.entrySet()) {
      TopicRelayer topicRelayer = entry.getValue();
      nextPollDelay = Math.min(nextPollDelay, topicRelayer.publishMessages());
    }
    return nextPollDelay;
  }

  /**
   * Helper class to fetch and publish messages from one topic.
   */
  private final class TopicRelayer {

    private final TopicId topicId;
    private String lastMessageId;
    private long nextPublishTimeMillis;

    TopicRelayer(TopicId topicId) {
      this.topicId = topicId;
    }

    /**
     * Fetches messages from the {@link MessagingContext} and publish them using {@link RuntimeClient}.
     *
     * @return delay in milliseconds till the next poll
     * @throws TopicNotFoundException if the TMS topic to fetch from does not exist
     * @throws IOException if failed to read from TMS or write to RuntimeClient
     */
    long publishMessages() throws TopicNotFoundException, IOException {
      long currentTimeMillis = System.currentTimeMillis();
      if (currentTimeMillis < nextPublishTimeMillis) {
        return nextPublishTimeMillis - currentTimeMillis;
      }

      try (
        CloseableIterator<Message> iterator = messagingContext.getMessageFetcher()
          .fetch(topicId.getNamespace(), topicId.getTopic(), fetchLimit, lastMessageId)
      ) {
        String[] messageId = new String[1];
        AtomicInteger messageCount = new AtomicInteger();

        runtimeClient.sendMessages(programRunId, topicId, new AbstractIterator<Message>() {
          @Override
          protected Message fetch() {
            Message message = iterator.next();
            messageId[0] = message.getId();
            messageCount.incrementAndGet();
            return message;
          }
        });

        // Update the lastMessageId if sendMessages succeeded
        lastMessageId = messageId[0];
        PROGRESS_LOG.debug("Fetched and published {} messages to topic {}", messageCount.get(), topicId);

        // If we fetched all messages, then delay the next poll by pollTimeMillis.
        // Otherwise, try to poll again immediately.
        nextPublishTimeMillis = System.currentTimeMillis();
        if (messageCount.get() >= fetchLimit) {
          return 0L;
        }
        nextPublishTimeMillis += pollTimeMillis;
        return pollTimeMillis;
      }
    }
  }
}
