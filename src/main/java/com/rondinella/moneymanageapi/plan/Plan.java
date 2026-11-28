package com.rondinella.moneymanageapi.plan;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A plan of the investments (table plan): its name; what it says is in its versions ({@link PlanVersion}). */
@Getter
@Setter
@Entity
public class Plan {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  String name;
  Instant createdAt;
}
