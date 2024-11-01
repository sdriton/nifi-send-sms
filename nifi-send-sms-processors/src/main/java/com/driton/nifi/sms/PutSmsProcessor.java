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

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

@Tags({ "aws", "sns", "sms", "notification", "amazon" })
@CapabilityDescription("This Processor sends SMS messages to each phone number provided in the incoming Flowfile. "
        + "It uses the Amazon AWS SNS Service SDK. The incoming Flowfile has to be in a json format. "
        + "Content of the incoming message is written to the content of the outgoing Flowfile. "
        + "Below is provided a sample Flowfile content that is required by the PutSms processor:"
        + "\nExample: {\"to\": [\"+15143334444\",\"+15143334445\"], \"body\": \"SMS Message\"}")
@WritesAttributes({
        @WritesAttribute(attribute = "aws.sms.status", description = "Status of the SMS message sent through AWS SNS"),
        @WritesAttribute(attribute = "aws.sms.error", description = "Error details if the SMS failed to send") })
public class PutSmsProcessor extends AbstractProcessor {
    private ComponentLog logger = null;
    public static final PropertyDescriptor AWS_ACCESS_KEY = new PropertyDescriptor.Builder().name("AWS Access Key")
            .description("AWS Access Key for SNS").required(true).addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .sensitive(true).build();

    public static final PropertyDescriptor AWS_SECRET_KEY = new PropertyDescriptor.Builder().name("AWS Secret Key")
            .description("AWS Secret Key for SNS").required(true).addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .sensitive(true).build();

    public static final PropertyDescriptor AWS_REGION = new PropertyDescriptor.Builder().name("AWS Region")
            .description("The AWS region to use").required(true).addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .defaultValue("us-east-1").build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
            .description("All FlowFiles that successfully send an SMS are routed to this relationship").build();

    public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
            .description("FlowFiles that fail to send an SMS are routed to this relationship").build();

    private static final String STATUS_SUCCESS = "Success";
    private static final String STATUS_FAILED = "Failed";
    private static final String SMS_RESPONSE_STATUS = "SmsResponseStatus";
    private static final String SMS_RESPONSE_MESSAGE = "SmsResponseMessage";

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descLst =  List.of(AWS_ACCESS_KEY, AWS_SECRET_KEY, AWS_REGION);
        this.descriptors = Collections.unmodifiableList(descLst);

        final Set<Relationship> relLst = Set.of(REL_SUCCESS, REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relLst);
        this.logger = getLogger();
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
            
        final String accessKey = context.getProperty(AWS_ACCESS_KEY).getValue();
        final String secretKey = context.getProperty(AWS_SECRET_KEY).getValue();
        final String region = context.getProperty(AWS_REGION).getValue();

        SnsClient snsClient = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        // Use an array to hold the flowFile, to work around final/effectively final
        // issue
        final FlowFile[] flowFileHolder = new FlowFile[] { flowFile };

        // Read JSON content from the incoming flow file
        try (InputStream inputStream = session.read(flowFile)) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(inputStream);

            // Extract phone numbers (as a list of strings) from the "to" field
            String toField = jsonNode.get("to").asText();
            @SuppressWarnings("unchecked")
            List<String> phoneNumbers = objectMapper.readValue(toField, List.class);

            // Extract the message body from the "body" field
            String messageBody = jsonNode.get("body").asText();

            // Send SMS to each phone number
            // If send sms for a number fails just put an attribute to the flowfile and 
            // continue processing the rest of the phone numbers.
            // The flow will fail if an exception is thrown.
            for (String phoneNumber : phoneNumbers) {
                HashMap<String, String> response = doSendSms(snsClient, phoneNumber, messageBody);
                
                String responseStatus = response.get(SMS_RESPONSE_STATUS);
                if(STATUS_FAILED.equals(responseStatus)) {
                    flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.error."+phoneNumber, response.get(SMS_RESPONSE_MESSAGE));
                } else if(STATUS_SUCCESS.equals(responseStatus)){
                    flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.status."+phoneNumber, response.get(SMS_RESPONSE_MESSAGE));
                }
            }
            session.transfer(flowFileHolder[0], REL_SUCCESS);
        } catch (Exception ex) {
            logger.error("Failed to process flow file", ex);
            flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.error", ex.getMessage());
            session.transfer(flowFileHolder[0], REL_FAILURE);
        } finally {
            snsClient.close();
        }
    }

    /**
     * This method sends a SMS message to a phoneNumber.
     * @param snsClient the initialized SnsClient instance.
     * @param phoneNumber the phone number.
     * @param messageBody the message to send.
     * @return a {@code HashMap<String, String>} that contains the response status and the reponse message.
     */
    private HashMap<String, String> doSendSms(SnsClient snsClient, String phoneNumber, String messageBody) {
        HashMap<String, String> result = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        try {
            PublishRequest request = PublishRequest.builder().message(messageBody).phoneNumber(phoneNumber).build();
            PublishResponse response = snsClient.publish(request);
            String message = sb.append("SMS send to ").append(phoneNumber).append(": ").append(response.messageId()).toString();
            logger.info(message);
            result.put(SMS_RESPONSE_STATUS, STATUS_SUCCESS);
            result.put(SMS_RESPONSE_MESSAGE, message);
            return result;
        } catch (Exception e) {
            String message = sb.append("Failed to send SMS to ").append(phoneNumber).append(": ").append(e.getMessage())
                    .toString();
            logger.info(message);
            result.put(SMS_RESPONSE_STATUS, STATUS_FAILED);
            result.put(SMS_RESPONSE_MESSAGE, message);
            return result;
        }
    }
}
