# MoneyManage

## Start program
You need to download and open Docker

You don't need to dockerize, spring will do it for you.
Spring uses compose.yaml
To deploy use docker-compose.yaml

## Setup
Endpoints http://127.0.0.1:8080/swagger-ui/index.html

To setup do the following:

/api/banks/transactions/{bankName}/upload
where _bankName_ is the bank you are uploading the csv

/api/banks/transactions/accounts/{accountName}/computeCumulativeAmount/{amountToday}
where you have to set in _amountToday_ the amount that you have today (or when you created the csv)