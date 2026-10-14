package com.kama.jchatmind.agent.tools.test;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionToolsTest {

    @Test
    void productionProfileKeepsDateButDoesNotRegisterMockCityOrWeather() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.register(CityTool.class, WeatherTool.class, DateTool.class);
            context.refresh();

            assertTrue(context.getBeansOfType(CityTool.class).isEmpty());
            assertTrue(context.getBeansOfType(WeatherTool.class).isEmpty());
            assertEquals(1, context.getBeansOfType(DateTool.class).size());
        }
    }
}
