package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.mappers.TransactionMapper;
import com.rondinella.moneymanageapi.repositories.TransactionRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

@Service
public class TransactionService {
  final
  TransactionRepository transactionRepository;
  TransactionMapper transactionMapper = TransactionMapper.INSTANCE;

  public TransactionService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  public List<TransactionDto> findAllTransactions() {
    return transactionMapper.toDto(transactionRepository.findAll());
  }

  public URI addTransaction(TransactionDto transactionDto) {
    transactionRepository.saveAndFlush(transactionMapper.toEntity(transactionDto));
    return null;
  }
}
