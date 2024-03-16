package com.rondinella.moneymanageapi.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControllerAPI {
  @GetMapping("/test")
  String test(){
    return "ciao";
  }
}
