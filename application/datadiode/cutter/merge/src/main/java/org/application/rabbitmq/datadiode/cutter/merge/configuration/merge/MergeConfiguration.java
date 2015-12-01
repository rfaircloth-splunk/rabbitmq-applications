package org.application.rabbitmq.datadiode.cutter.merge.configuration.merge;

import com.thoughtworks.xstream.XStream;
import org.application.rabbitmq.datadiode.model.message.ExchangeMessage;
import org.application.rabbitmq.datadiode.service.RabbitMQService;
import org.application.rabbitmq.datadiode.service.RabbitMQServiceImpl;
import org.application.rabbitmq.datadiode.cutter.model.Segment;
import org.application.rabbitmq.datadiode.cutter.model.SegmentHeader;
import org.application.rabbitmq.datadiode.cutter.util.StreamUtils;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultClassMapper;
import org.springframework.amqp.support.converter.JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by marcelmaatkamp on 24/11/15.
 */
@Configuration
@EnableScheduling
public class MergeConfiguration implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(MergeConfiguration.class);

    public static final String X_SHOVELLED = "x-shovelled";
    public static final String SRC_EXCHANGE = "src-exchange";
    public static final String SRC_QUEUE = "src-queue";

    Map<SegmentHeader, TreeSet<Segment>> uMessages = new ConcurrentHashMap();

    @Autowired
    XStream xStream;

    @Autowired
    private volatile RabbitTemplate rabbitTemplate;


    @Bean
    public JsonMessageConverter jsonMessageConverter() {
        JsonMessageConverter jsonMessageConverter = new JsonMessageConverter();
        jsonMessageConverter.setJsonObjectMapper(objectMapper());
        jsonMessageConverter.setClassMapper(defaultClassMapper());
        return jsonMessageConverter;
    }

    @Bean
    ObjectMapper objectMapper() {
        ObjectMapper jsonObjectMapper = new ObjectMapper();
        jsonObjectMapper
                .configure(
                        DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
                        false);
        jsonObjectMapper.setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
        return jsonObjectMapper;
    }

    @Bean
    public DefaultClassMapper defaultClassMapper() {
        DefaultClassMapper defaultClassMapper = new DefaultClassMapper();
        return defaultClassMapper;
    }

    @PostConstruct
    void init() {
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
    }


    @Autowired
    Environment environment;

    @Bean
    MessageDigest messageDigest() throws NoSuchAlgorithmException {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        return messageDigest;
    }

    @Bean
    StreamUtils streamUtils() {
        StreamUtils streamUtils = new StreamUtils();
        return streamUtils;
    }

    @Bean
    RabbitMQService rabbitMQService() {
        RabbitMQService rabbitMQService = new RabbitMQServiceImpl();
        return rabbitMQService;
    }


    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "${application.datadiode.cutter.queue}", durable = "true"),
                    exchange = @Exchange(value = "${application.datadiode.cutter.exchange}", durable = "true", autoDelete = "false", type = "fanout"))
    )
    public void onMessage(Message message) {
            String xml = new String(message.getBody());
            Object o = xStream.fromXML(xml);


        if (o instanceof SegmentHeader) {
            SegmentHeader segmentHeader = (SegmentHeader) o;
            if (log.isDebugEnabled()) {
                log.debug("header(" + segmentHeader.uuid + ") of size(" + segmentHeader.blockSize + ") and count(" + segmentHeader.count + ")");
            }
            boolean found = false;
            for (SegmentHeader s : uMessages.keySet()) {
                if (s.uuid.equals(segmentHeader.uuid)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                uMessages.put(segmentHeader, new TreeSet<Segment>());
                if (log.isDebugEnabled()) {
                    log.debug("starting message(" + segmentHeader.uuid + ") of size(" + segmentHeader.blockSize + ") and count(" + segmentHeader.count + ")");
                }
            }
        } else if (o instanceof Segment) {

            Segment segment = (Segment) o;
            if (log.isDebugEnabled()) {
               // log.debug("segment(" + xStream.toXML(segment) + ")");
            }
            for (SegmentHeader segmentHeader : uMessages.keySet()) {
                if (segmentHeader.uuid.equals(segment.uuid)) {
                    segmentHeader.update = new Date();
                    Set<Segment> messages = uMessages.get(segmentHeader);

                    messages.add(segment);
                    if (messages.size() == segmentHeader.count + 1) {
                        try {
                            ExchangeMessage messageFromStream = StreamUtils.reconstruct(segmentHeader, messages);
                            rabbitMQService().sendExchangeMessage(messageFromStream);
                            uMessages.remove(segmentHeader);
                        } catch (IOException e) {
                            log.error("Exception: " + e);
                        }
                    }
                }
            }
        } else {
            log.error("Error: Unknown object: " + o);
        }

    }

    /**
     * Cleaup function
     *
     * @throws MalformedURLException
     */
    @Scheduled(fixedRate = 5000)
    public void cleanup() throws MalformedURLException {
        if (uMessages.keySet().size() > 0) {
            log.info("concurrent active messages: " + uMessages.keySet().size());
        }

        for (SegmentHeader segmentHeader : uMessages.keySet()) {
            if (segmentHeader.update != null && (new Date().getTime() - segmentHeader.update.getTime()) > 25000) {
                log.info("cleaning up " + segmentHeader.uuid + ", got(" + uMessages.get(segmentHeader).size() + "), missing(" + ((segmentHeader.count + 2) - uMessages.get(segmentHeader).size()) + ")");
                uMessages.remove(segmentHeader);
            }

        }
    }
}
