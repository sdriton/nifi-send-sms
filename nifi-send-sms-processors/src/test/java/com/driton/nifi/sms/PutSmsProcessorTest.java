/* 
    Copyright 2024 Driton Salihu

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License. 
*/
   
package com.driton.nifi.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PutSmsProcessorTest {

    private TestRunner testRunner;

    @BeforeEach
    public void init() {
        testRunner = TestRunners.newTestRunner(PutSmsProcessor.class);
    }

    @Test
    void testWrongAWSInformation() {
        testRunner.setProperty(PutSmsProcessor.AWS_ACCESS_KEY, "AccessKey");
        testRunner.setProperty(PutSmsProcessor.AWS_SECRET_KEY, "AccessKeySecret");
        testRunner.setProperty(PutSmsProcessor.AWS_REGION, "us-east-1");
        String flowFileContent = "{\"to\": \"[\"+15148887777\",\"+15148887779\"]\", \"body\": \"SMS Message.\"}";
        // Enqueue a flow file
        testRunner.enqueue(flowFileContent);
        
        //execute 1 run - similar to NIFI UI's run once
        testRunner.run(1);

         //assert the input Q is empty and the flowfile is processed
        testRunner.assertQueueEmpty();

         //get the flowfiles from Failure Q
        List<MockFlowFile> results = testRunner.getFlowFilesForRelationship(PutSmsProcessor.REL_FAILURE);
        assertEquals(1, results.size());

        MockFlowFile outputFlowfile = results.get(0);
        String outputFlowfileContent = new String(testRunner.getContentAsByteArray(outputFlowfile));
        System.out.println("Output flowfile content: " + outputFlowfileContent);
        assertEquals(flowFileContent, outputFlowfileContent);
    }
}
