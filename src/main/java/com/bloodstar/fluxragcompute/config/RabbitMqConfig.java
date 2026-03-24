package com.bloodstar.fluxragcompute.config;

import com.bloodstar.fluxragcompute.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Queue documentQueue() {
        return new Queue(MqConstants.DOCUMENT_QUEUE, true);
    }

    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(MqConstants.DOCUMENT_EXCHANGE, true, false);
    }

    @Bean
    public Binding documentBinding(Queue documentQueue, DirectExchange documentExchange) {
        return BindingBuilder.bind(documentQueue)
                .to(documentExchange)
                .with(MqConstants.DOCUMENT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
