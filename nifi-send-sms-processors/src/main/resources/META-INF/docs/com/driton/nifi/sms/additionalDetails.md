<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

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
* **AWS Region** - _\[your region (us-east-1)\]_
