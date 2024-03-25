package com.rondinella.moneymanageapi.mappers;

import com.rondinella.moneymanageapi.dtos.BankTransactionDto;
import com.rondinella.moneymanageapi.enitities.BankTransaction;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Mapper
public interface BankTransactionMapper {
  BankTransactionMapper INSTANCE = new BankTransactionMapperImpl(); // Singleton instance

  BankTransactionDto toDto(BankTransaction entity);

  BankTransaction toEntity(BankTransactionDto entity);

  List<BankTransactionDto> toDto(List<BankTransaction> entity);

  List<BankTransaction> toEntity(List<BankTransactionDto> entity);

  default BankTransactionDto toDtoFromRevolut(Map<String, Object> rowData) {
    if (rowData == null) {
      return null;
    }
    BankTransactionDto dto = new BankTransactionDto();
    dto.setAccount("Revolut_" + rowData.get("Product"));
    dto.setDatetime(Timestamp.valueOf((String) rowData.get("Started Date")));
    dto.setDescription((String) rowData.get("Description"));
    dto.setAmount(new BigDecimal((String) rowData.get("Amount")));
    dto.setFee(new BigDecimal((String) rowData.get("Fee")));
    dto.setCurrency((String) rowData.get("Currency"));
    return dto;
  }
}
