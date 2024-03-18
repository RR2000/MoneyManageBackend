package com.rondinella.moneymanageapi.enitities.ids;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.sql.Timestamp;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionId implements Serializable {
  String account;
  Timestamp startedDate;
  Timestamp completedDate;
  String description;
}