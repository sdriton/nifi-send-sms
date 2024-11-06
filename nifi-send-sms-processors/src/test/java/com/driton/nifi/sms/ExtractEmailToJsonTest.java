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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.apache.nifi.processor.Relationship;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExtractEmailToJsonTest {

    private TestRunner runner;
    String from = "sender@ethereal.email";
    String to = "+11239994444@ethereal.email";
    String subject = "Test email";
    String message = "This is my text to send as SMS";
    String hostName = "locahost";

    GenerateEmail emailGenerator = new GenerateEmail(from, to, subject, message, hostName);

    @BeforeEach
    public void init() {
        final ExtractEmailToJson processor = new ExtractEmailToJson();
        processor.init(null);
        runner = TestRunners.newTestRunner(processor);
    }

    @Test
    void testGetRelationships() {
        ExtractEmailToJson processor = (ExtractEmailToJson) runner.getProcessor();
        final Set<Relationship> relationships = processor.getRelationships();
        assertEquals(2, relationships.size());
        assertTrue(relationships.contains(ExtractEmailToJson.REL_FAILURE));
        assertTrue(relationships.contains(ExtractEmailToJson.REL_SUCCESS));
    }

    @Test
    void testValidEmailReadFromFile() {
        byte[] fileContent = emailGenerator.fromFile("sample-smtp-email.txt");
        String fileContentAsText = new String(fileContent, StandardCharsets.UTF_8);
        System.out.println(fileContentAsText);

        runner.enqueue(fileContent);
        runner.run();
        runner.assertQueueEmpty();

        runner.assertTransferCount(ExtractEmailToJson.REL_SUCCESS, 1);
        runner.assertTransferCount(ExtractEmailToJson.REL_FAILURE, 0);

        final List<MockFlowFile> mockFlowFiles = runner.getFlowFilesForRelationship(ExtractEmailToJson.REL_SUCCESS);
        final MockFlowFile mockFlowFile = mockFlowFiles.get(0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = null;
        try {
            node = mapper.readTree(mockFlowFile.getContent());
        } catch (Exception e) {
        }
        JsonNode actualTo = node.get("to");
        String firstPhoneNumber = null;
        try {
            List<String> toList = mapper.readerForListOf(String.class).readValue(actualTo);
            firstPhoneNumber = toList.get(0);
        } catch(Exception e){

        }
        Assertions.assertEquals("+11004445555", firstPhoneNumber);
    }

    @Test
    void testValidEmailGenerated() {
        byte[] fileContent = emailGenerator.emailMessage(to);
        String fileContentAsText = new String(fileContent, StandardCharsets.UTF_8);
        System.out.println(fileContentAsText);

        runner.enqueue(fileContent);
        runner.run();
        runner.assertQueueEmpty();

        runner.assertTransferCount(ExtractEmailToJson.REL_SUCCESS, 1);
        runner.assertTransferCount(ExtractEmailToJson.REL_FAILURE, 0);

        final List<MockFlowFile> mockFlowFiles = runner.getFlowFilesForRelationship(ExtractEmailToJson.REL_SUCCESS);
        final MockFlowFile mockFlowFile = mockFlowFiles.get(0);
        String actualJson = mockFlowFile.getContent();
        String expectedJson = "{\"to\":[\"+11239994444\"],\"body\":\"This is my text to send as SMS\"}";
        Assertions.assertEquals(expectedJson, actualJson);
    }
}
