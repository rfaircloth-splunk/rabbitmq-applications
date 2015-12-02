package org.application.rabbitmq.datadiode.cutter.cut.configuration.listener;

import com.thoughtworks.xstream.XStream;
import org.application.rabbitmq.datadiode.model.message.ExchangeMessage;
import org.application.rabbitmq.datadiode.service.RabbitMQService;
import org.application.rabbitmq.datadiode.cutter.util.StreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitManagementTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Created by marcelmaatkamp on 15/10/15.
 */
@Resource
public class ExchangeMessageConverterListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ExchangeMessageConverterListener.class);

    @Autowired
    RabbitMQService rabbitMQService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitManagementTemplate rabbitManagementTemplate;

    @Autowired
    XStream xStream;

    @Value(value = "${application.datadiode.cutter.size}")
    int maxMessageSize;

    @Value(value = "${application.datadiode.cutter.redundancyFactor}")
    int redundancyFactor;

    @Autowired
    Exchange cutterExchange;

    /**
     * @param message
     * @throws Exception
     */
    @Override
    public void onMessage(Message message) {
        try {
            ExchangeMessage exchangeMessage = rabbitMQService.getExchangeMessage(rabbitManagementTemplate, message);
            List<Message> messages = null;

            messages = StreamUtils.cut(exchangeMessage, maxMessageSize, redundancyFactor);

            if (log.isDebugEnabled()) {
                log.debug("cutting (message.length(" + message.getBody().length + ") * redundancy(" + redundancyFactor + ")) into " + messages.size() + " messages of " + maxMessageSize + " bytes..");
            }

            for (Message m : messages) {
                rabbitTemplate.convertAndSend(cutterExchange.getName(), null, m);
            }

        } catch (IOException e) {
            log.error("Exception: ", e);
        }
    }
}
