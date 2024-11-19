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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.nifi.processor.Relationship;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

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

    // @Test
    void testRateLimiter(){
        PutSms processor = (PutSms) testRunner.getProcessor();
        Dotenv dotEnv = null; 
        try { 
            dotEnv = Dotenv.configure().ignoreIfMissing().systemProperties().load();
        } catch(Exception e){
            System.out.println("DotEnv file is missing."); 
            return;
        }

        String awsAccessKey = dotEnv.get("AWS_ACCESS_KEY");
        String awsAccessSecret = dotEnv.get("AWS_ACCESS_SECRET");
        

        SnsClient snsClient = SnsClient.builder().region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKey, awsAccessSecret)))
                .build();
        List<String> phoneNumbers = new ArrayList<>();
        for(int i = 0; i < 50; ++i){
            phoneNumbers.add("+111133344" + String.format("%02d", i));
        }

        RateLimiter limiter = null;
        // The limit of requests to AWS SNS Publish SMS is 20 requests per second.
        int limitForPeriod = 18;
        // The timeout between batches of 20 requests. It should be 1 second as per AWS Limits.
        long timeoutDuration = 1500;
        // The limit of refresh period 
        long limitRefreshPeriod = 1500;
        
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofMillis(limitRefreshPeriod))
                .timeoutDuration(Duration.ofMillis(timeoutDuration))
                .build();

        limiter = RateLimiter.of("awssnscalllimiter_rps", config);
        List<String> listOfTimestamps = new ArrayList<>();
        String message = null;
        for (String phoneNumber : phoneNumbers) {
            try {
                message = processor.doSendSms(snsClient, limiter, phoneNumber, "Test rate limiter.");
                System.out.println(new Date().toString());
                Calendar rightNow = Calendar.getInstance();
                int second = rightNow.get(Calendar.SECOND);
                int minute = rightNow.get(Calendar.MINUTE);
                String stamp = String.format("%s",   minute + second + "");
                listOfTimestamps.add(stamp);
                assertNotNull(message);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }

        Map<String, Long> counts = listOfTimestamps.stream()
            .collect(Collectors.groupingBy(value -> value, Collectors.counting()));

        // Print the result
        counts.forEach((key, count) -> { 
            System.out.println(key + ": " + count);
            assertTrue(count <= 20);
        });
    }
}
