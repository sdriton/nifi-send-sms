# Description
This project contains two processors which can be used together to build an email to sms message integration :
* **ExtractEmailToJson**
* **PutSMS**

## ExtractEmailToJson Processor

This Processor parses a SMTP message and extracts message body and recepients (to) fields from the raw email and creates a json structure as a FlowFile. This FlowFile is used as an incoming FlowFile for PutSMS processor. Below is provided a sample Flowfile content :
```
{
    "to": ["+15143334444", "+15143334445" ], 
    "body": "SMS Message"
}
```

## PutSms Processor


This Processor sends SMS messages to each phone number provided in the incoming Flowfile. It uses the AWS SNS Service SDK. The incoming Flowfile has to be in a json format.
Content of the incoming message is written to the content of the outgoing Flowfile.
Below is provided a sample Flowfile content that is required by the PutSms processor:

```
{ 
    "to": ["+15148887777","+15148887779"],
    "body": "SMS Message."
}
```

**Processor's static properties:**

* **AWS Access Key** - _\[Your Access Key\]_
* **AWS Secret Key** - _\[Your Secret Key\]_
* **AWS Region** - _\[us-east-1\]_
* **Use rate limiting** - _[true | false]_  Default is false.


## Getting started with the project
### Build

Use maven commands to compile, install and run tests or use the shell scripts build.sh to build the project and deploy the NAR Archive to the NiFi lib folder.

Build the project:
```
mvn clean install
```

### Test
The following environment variables must be set before running tests:

````
export AWS_ACCESS_KEY=<YourAccessKey>
export AWS_ACCESS_SECRET=<YourSecretKey>
````

Run the maven test command
````
mvn clean test
````


### Deploy
There's a script that semi-automates the deployment of the NAR file to the Apache NiFi lib folder. Make sure you stop Apache NiFi before deploying the nar file. Restart it after the copying is done.

Before running the script you should set NIFI_LIB environment variable.

````
export NIFI_LIB=/your/path/to/nifi/lib
````


````
./copy-to-nifi-lib.sh
````


### Requirements
* Java 17 or later
* Maven 3.9.9 or later

