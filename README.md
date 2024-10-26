# PutSms

## Description:

This Processor sends SMS messages to each phone number provided in the incoming Flowfile. It uses the Amazon AWS SNS Service SDK. The incoming Flowfile has to be in a json format.
Content of the incoming message is written to the content of the outgoing Flowfile.
Below is provided a sample Flowfile content that is required by the PutSms processor:

```
{ 
    "to": "["+15148887777","+15148887779"]",
	"body": "SMS Message."
}
```

**Processor's static properties:**

* **AWS Access Key** - _\[Your Access Key\]_
* **AWS Secret Key** - _\[Your Secret Key\]_
* **AWS Region** - _\[us-east-1\]_