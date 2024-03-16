package com.rondinella.moneymanageapi.mappers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.enitities.Transaction;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper
public interface TransactionMapper {
  TransactionMapper INSTANCE = new TransactionMapperImpl(); // Singleton instance

  TransactionDto toDto(Transaction entity);
  Transaction toEntity(TransactionDto entity);

  List<TransactionDto> toDto(List<Transaction> entity);
  List<Transaction> toEntity(List<TransactionDto> entity);
}
