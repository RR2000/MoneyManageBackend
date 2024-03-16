package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.enitities.Transaction;
import com.rondinella.moneymanageapi.mappers.TransactionMapper;
import com.rondinella.moneymanageapi.repositories.TransactionRepository;
import org.springframework.stereotype.Service;

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

  public List<TransactionDto> addTransaction(List<TransactionDto> transactionDto) {
    List<Transaction> txToAdd = transactionMapper.toEntity(transactionDto);
    List<Transaction> txAdded = transactionRepository.saveAllAndFlush(txToAdd);
    return transactionMapper.toDto(txAdded);
  }
}
