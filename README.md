# PutSms Processor

Sends an SMS message to each phone number retrieved from the incoming Flowfile using AWS SNS. The incoming Flowfile has to be in a json format.
The format of the FlowFile should be like the example below :

```
{ 
    "to": "["+15148887777","+15148887779"]",
	"body": "SMS Message."
}
```
