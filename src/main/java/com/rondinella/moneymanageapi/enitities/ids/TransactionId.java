package com.rondinella.moneymanageapi.enitities.ids;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.sql.Timestamp;

@Getter
@Setter
public class TransactionId implements Serializable {
  String account;
  Timestamp startedDate;
  Timestamp completedDate;
}
