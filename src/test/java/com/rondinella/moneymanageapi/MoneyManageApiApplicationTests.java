package com.rondinella.moneymanageapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

@SpringBootTest
class MoneyManageApiApplicationTests {

  @Test
  void contextLoads() {
    // Your test code here
  }

  @Configuration // This class serves as a test configuration
  static class TestConfiguration {
    // Define necessary configurations for your tests here
  }
}
