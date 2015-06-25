/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.axis2.transport.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.rabbitmq.utils.AxisRabbitMQException;
import org.apache.axis2.transport.rabbitmq.utils.RabbitMQConstants;
import org.apache.axis2.transport.rabbitmq.utils.RabbitMQUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;

/**
 * Class that performs the actual sending of a RabbitMQ AMQP message,
 */

public class RabbitMQMessageSender {
    private static final Log log = LogFactory.getLog(RabbitMQMessageSender.class);

    private Connection connection = null;
    private String targetEPR = null;
    private Hashtable<String, String> properties;

    /**
     * Create a RabbitMQSender using a ConnectionFactory and target EPR
     *
     * @param factory   the ConnectionFactory
     * @param targetEPR the targetAddress
     */
    public RabbitMQMessageSender(RabbitMQConnectionFactory factory, String targetEPR) {
        try {
            this.connection = factory.getConnectionPool();
        } catch (IOException e) {
            handleException("Error while creating connection pool", e);
        }
        this.targetEPR = targetEPR;
        if (!targetEPR.startsWith(RabbitMQConstants.RABBITMQ_PREFIX)) {
            handleException("Invalid prefix for a AMQP EPR : " + targetEPR);
        } else {
            this.properties = BaseUtils.getEPRProperties(targetEPR);
        }
    }

    /**
     * Perform the creation of exchange/queue and the Outputstream
     *
     * @param message    the RabbitMQ AMQP message
     * @param msgContext the Axis2 MessageContext
     */
    public void send(RabbitMQMessage message, MessageContext msgContext) throws
            AxisRabbitMQException, IOException {

        String exchangeName = null;
        AMQP.BasicProperties basicProperties = null;
        byte[] messageBody = null;
        if (connection != null) {
            Channel channel = null;
            String queueName = properties.get(RabbitMQConstants.QUEUE_NAME);
            String routeKey = properties
                    .get(RabbitMQConstants.QUEUE_ROUTING_KEY);
            exchangeName = properties.get(RabbitMQConstants.EXCHANGE_NAME);
            String exchangeType = properties
                    .get(RabbitMQConstants.EXCHANGE_TYPE);
            String durable = properties.get(RabbitMQConstants.EXCHANGE_DURABLE);
            String replyTo = properties.get(RabbitMQConstants.RABBITMQ_REPLY_TO);

            //if the user defined any replyTo value it will be set
            if (replyTo != null) {
                message.setReplyTo(replyTo);
            }

            if (queueName == null || queueName.equals("")) {
                log.info("No queue name is specified");
            }

            if (routeKey == null && !"x-consistent-hash".equals(exchangeType)) {
                if (queueName == null || queueName.equals("")) {
                    log.info("Routing key is not specified");
                } else {
                    log.info(
                            "Routing key is not specified. Using queue name as the routing key.");
                    routeKey = queueName;
                }
            }

            channel = connection.createChannel();
            log.debug("Creating a new channel.");

            //Declaring the queue
            if (queueName != null && !queueName.equals("")) {

                Boolean queueAvailable = false;
                try {
                    if (!channel.isOpen()) {
                        channel = connection.createChannel();
                        log.debug("Channel is not open. Creating a new channel.");
                    }
                    // check availability of the named queue
                    // if an error is encountered, including if the queue does not exist and if the
                    // queue is exclusively owned by another connection
                    channel.queueDeclarePassive(queueName);
                    queueAvailable = true;
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Queue :" + queueName + " not found or already declared exclusive. Trying to declare the queue.");
                    }
                }

                if (!queueAvailable) {
                    // Declare the named queue if it does not exists.
                    if (!channel.isOpen()) {
                        channel = connection.createChannel();
                        log.debug("Channel is not open. Creating a new channel.");
                    }
                    try {
                        channel.queueDeclare(queueName, RabbitMQUtils
                                        .isDurableQueue(properties),
                                RabbitMQUtils
                                        .isExclusiveQueue(properties),
                                RabbitMQUtils
                                        .isAutoDeleteQueue(properties),
                                null);

                    } catch (IOException e) {
                        handleException("Error while creating queue: " + queueName, e);
                        return;
                    }
                }
            }

            //Declaring the exchange
            if (exchangeName != null && !exchangeName.equals("")) {
                Boolean exchangeAvailable = false;
                try {
                    if (!channel.isOpen()) {
                        channel = connection.createChannel();
                        log.debug("Channel is not open. Creating a new channel.");
                    }
                    // check availability of the named exchange.
                    // The server will raise an IOException
                    // if the named exchange already exists.
                    channel.exchangeDeclarePassive(exchangeName);
                    exchangeAvailable = true;
                } catch (IOException e) {
                    log.info("Exchange :" + exchangeName + " not found.Declaring exchange.");
                }

                if (!exchangeAvailable) {
                    // Declare the named exchange if it does not exists.
                    if (!channel.isOpen()) {
                        channel = connection.createChannel();
                        log.debug("Channel is not open. Creating a new channel.");
                    }
                    try {
                        if (exchangeType != null
                                && !exchangeType.equals("")) {
                            if (durable != null && !durable.equals("")) {
                                channel.exchangeDeclare(exchangeName,
                                        exchangeType,
                                        Boolean.parseBoolean(durable));
                            } else {
                                channel.exchangeDeclare(exchangeName,
                                        exchangeType, true);
                            }
                        } else {
                            channel.exchangeDeclare(exchangeName, "direct", true);
                        }
                    } catch (IOException e) {
                        handleException("Error occurred while declaring exchange.", e);
                    }
                }

                if (queueName != null && !"x-consistent-hash".equals(exchangeType)) {
                    // Create bind between the queue &
                    // provided routeKey
                    try {
                        if (!channel.isOpen()) {
                            channel = connection.createChannel();
                            log.debug("Channel is not open. Creating a new channel.");
                        }
                        // no need to have configure permission to
                        // perform channel.queueBind
                        channel.queueBind(queueName, exchangeName, routeKey);
                    } catch (IOException e) {
                        handleException(
                                "Error occurred while creating the bind between the queue: "
                                        + queueName + " & exchange: " + exchangeName + " with routekey " + routeKey, e);
                    }
                }

            }

            AMQP.BasicProperties.Builder builder = buildBasicProperties(message);

            // set delivery mode (default is Persistent): 1=NonPersistent , 2=Persistent
            String deliveryModeString = properties
                    .get(RabbitMQConstants.QUEUE_DELIVERY_MODE);
            int deliveryMode = RabbitMQConstants.DEFAULT_DELIVERY_MODE;
            if (deliveryModeString != null) {
                deliveryMode = Integer.parseInt(deliveryModeString);
            }
            builder.deliveryMode(deliveryMode);

            basicProperties = builder.build();
            OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
            MessageFormatter messageFormatter = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                messageFormatter = MessageProcessorSelector.getMessageFormatter(msgContext);
            } catch (AxisFault axisFault) {
                throw new AxisRabbitMQException(
                        "Unable to get the message formatter to use",
                        axisFault);
            }

            //server plugging should be enabled before using x-consistent hashing
            //for x-consistent-hashing only exchangeName, exchangeType and routingKey should be
            // given. Queue/exchange creation, bindings should be done at the broker
            try {
                // generate random value as routeKey if the exchangeType is
                // x-consistent-hash type
                if (exchangeType != null
                        && exchangeType.equals("x-consistent-hash")) {
                    routeKey = UUID.randomUUID().toString();
                }

            } catch (UnsupportedCharsetException ex) {
                handleException(
                        "Unsupported encoding "
                                + format.getCharSetEncoding(), ex);
            }
            try {
                messageFormatter.writeTo(msgContext, format, out, false);
                messageBody = out.toByteArray();
            } catch (IOException e) {
                handleException("IO Error while creating BytesMessage", e);
            } finally {
                if (out != null) {
                    out.close();
                    channel.abort();
                }
            }

            try {
                if (connection != null) {
                    try {
                        if ((channel == null) || !channel.isOpen()) {
                            channel = connection.createChannel();
                            log.debug("Channel is not open or unavailable. Creating a new channel.");
                        }
                        if (exchangeName != null && exchangeName != "") {
                            channel.basicPublish(exchangeName, routeKey, basicProperties,
                                    messageBody);
                        } else {
                            channel.basicPublish("", routeKey, basicProperties,
                                    messageBody);
                        }
                    } catch (IOException e) {
                        handleException(
                                "Error while publishing the message",
                                e);
                    } finally {
                        if (channel != null) {
                            channel.close();
                        }
                    }

                }
            } catch (IOException e) {
                handleException("Error while publishing message to the queue ", e);
            }
        }
    }

    /**
     * Close the connection
     */
    public void close() {
        if (connection != null && connection.isOpen()) {
            try {
                connection.close();
            } catch (IOException e) {
                handleException("Error while closing the connection ..", e);
            } finally {
                connection = null;
            }
        }
    }

    /**
     * Build and populate the AMQP.BasicProperties using the RabbitMQMessage
     *
     * @param message the RabbitMQMessage to be used to get the properties
     * @return AMQP.BasicProperties object
     */
    private AMQP.BasicProperties.Builder buildBasicProperties(RabbitMQMessage message) {
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties().builder();
        builder.messageId(message.getMessageId());
        builder.contentType(message.getContentType());
        builder.replyTo(message.getReplyTo());
        builder.correlationId(message.getCorrelationId());
        builder.contentEncoding(message.getContentEncoding());
        Map<String, Object> headers = message.getHeaders();
        headers.put(RabbitMQConstants.SOAP_ACTION, message.getSoapAction());
        builder.headers(headers);
        return builder;
    }

    private void handleException(String s) {
        log.error(s);
        throw new AxisRabbitMQException(s);
    }

    private void handleException(String message, Exception e) {
        log.error(message, e);
        throw new AxisRabbitMQException(message, e);
    }

}