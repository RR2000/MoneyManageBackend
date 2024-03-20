package com.rondinella.moneymanageapi.mappers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.enitities.Transaction;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Mapper
public interface TransactionMapper {
  TransactionMapper INSTANCE = new TransactionMapperImpl(); // Singleton instance

  TransactionDto toDto(Transaction entity);

  Transaction toEntity(TransactionDto entity);

  List<TransactionDto> toDto(List<Transaction> entity);

  List<Transaction> toEntity(List<TransactionDto> entity);

  default TransactionDto toDto(Map<String, Object> rowData) {
    if (rowData == null) {
      return null;
    }
    TransactionDto dto = new TransactionDto();
    dto.setAccount("Revolut_" + rowData.get("Product"));
    dto.setDatetime(Timestamp.valueOf((String) rowData.get("Started Date")));
    dto.setDescription((String) rowData.get("Description"));
    dto.setAmount(new BigDecimal((String) rowData.get("Amount")));
    dto.setFee(new BigDecimal((String) rowData.get("Fee")));
    dto.setCurrency((String) rowData.get("Currency"));
    return dto;
  }
}
