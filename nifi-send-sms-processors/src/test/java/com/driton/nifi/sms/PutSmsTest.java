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

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.apache.nifi.processor.Relationship;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

class PutSmsTest {

    private TestRunner testRunner;
    private String flowFileContent = "{\"to\": [\"+11113334444\",\"+11118887779\"], \"body\": \"SMS Message.\"}";

    @BeforeEach
    public void init() {
        final PutSms putSMS = new PutSms();
        putSMS.init(null);
        testRunner = TestRunners.newTestRunner(putSMS);
    }

    @Test
    void testGetRelationships() {
        PutSms putSMS = (PutSms) testRunner.getProcessor();
        final Set<Relationship> relationships = putSMS.getRelationships();
        assertEquals(2, relationships.size());
        assertTrue(relationships.contains(PutSms.REL_FAILURE));
        assertTrue(relationships.contains(PutSms.REL_SUCCESS));
    }

    @Test
    void testWithWrongAWSInformation() {
        testRunner.setProperty(PutSms.AWS_ACCESS_KEY, "awsAccessKey");
        testRunner.setProperty(PutSms.AWS_SECRET_KEY, "awsAccessSecret");
        testRunner.setProperty(PutSms.AWS_REGION, "us-east-1");

        // Enqueue a flow file
        testRunner.enqueue(flowFileContent);

        // execute 1 run - similar to NIFI UI's run once
        testRunner.run(1);

        // assert the input Q is empty and the flowfile is processed
        testRunner.assertQueueEmpty();

        // get the flowfiles from Failure Q
        List<MockFlowFile> results = testRunner.getFlowFilesForRelationship(PutSms.REL_FAILURE);
        assertEquals(1, results.size());

        MockFlowFile outputFlowfile = results.get(0);
        String outputFlowfileContent = new String(testRunner.getContentAsByteArray(outputFlowfile));
        System.out.println("Output flowfile content: " + outputFlowfileContent);
        assertEquals(flowFileContent, outputFlowfileContent);
    }

    @Test
    void testWithCorrectAWSInformation() {
        Dotenv dotEnv = null; 
        try { 
            dotEnv = Dotenv.configure().ignoreIfMissing().systemProperties().load();
        } catch(Exception e){
            System.out.println("DotEnv file is missing."); 
            return;
        }
        Comparator<DotenvEntry> c = new Comparator<DotenvEntry>() {
            @Override
            public int compare(DotenvEntry o1, DotenvEntry o2) {
                return o1.getKey().compareToIgnoreCase(o2.getKey());
            }
        };
        dotEnv.entries().stream().sorted(c).forEachOrdered(it -> System.out.println(it.getKey()));

        String awsAccessKey = dotEnv.get("AWS_ACCESS_KEY");
        String awsAccessSecret = dotEnv.get("AWS_ACCESS_SECRET");
        
        if(awsAccessKey == null || awsAccessSecret == null){
            // If AWS credentials are not set in the environment variables
            // skip the test because it's going to fail.
            System.out.println("AWS Credentials must be set in the environment variables.");
            return;
        }

        testRunner.setProperty(PutSms.AWS_ACCESS_KEY, awsAccessKey);
        testRunner.setProperty(PutSms.AWS_SECRET_KEY, awsAccessSecret);
        testRunner.setProperty(PutSms.AWS_REGION, "us-east-1");
        // Enqueue a flow file
        testRunner.enqueue(flowFileContent);

        // execute 1 run - similar to NIFI UI's run once
        testRunner.run(1);

        // assert the input Q is empty and the flowfile is processed
        testRunner.assertQueueEmpty();

        // get the flowfiles from Success Q
        List<MockFlowFile> results = testRunner.getFlowFilesForRelationship(PutSms.REL_SUCCESS);
        assertEquals(1, results.size());

        MockFlowFile outputFlowfile = results.get(0);
        String outputFlowfileContent = new String(testRunner.getContentAsByteArray(outputFlowfile));
        System.out.println("Output flowfile content: " + outputFlowfileContent);
        assertEquals(flowFileContent, outputFlowfileContent);
    }
}
