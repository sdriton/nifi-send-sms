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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
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

@SeeAlso({ ExtractEmailToJsonProcessor.class })
@InputRequirement(Requirement.INPUT_REQUIRED)
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

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descLst = List.of(AWS_ACCESS_KEY, AWS_SECRET_KEY, AWS_REGION);
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

        SnsClient snsClient = SnsClient.builder().region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        // Use an array to hold the flowFile, to work around final/effectively final
        // issue
        final FlowFile[] flowFileHolder = new FlowFile[] { flowFile };

        // Read JSON content from the incoming flow file
        try (InputStream inputStream = session.read(flowFile)) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(inputStream);

            // Extract the recepients (phone numbers)
            List<String> phoneNumbers = getPhoneNumberList(jsonNode);

            // Extract the message body from the "body" field
            String messageBody = jsonNode.get("body").asText();

            // Send SMS to each phone number
            // If send sms for a number fails just put an attribute to the flowfile and
            // continue processing the rest of the phone numbers.
            // The flow will fail if an exception is thrown.
            String message = null;
            for (String phoneNumber : phoneNumbers) {
                message = doSendSms(snsClient, phoneNumber, messageBody);
                flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.status." + phoneNumber, message);
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
     * This method extracts the phone numbers from the "to" JsonNode.
     * 
     * @param jsonNode the JsonNode containing the phone number array.
     * @return a {@code List<String>} that contains the phone number.
     */
    private List<String> getPhoneNumberList(JsonNode jsonNode) {
        // Extract phone numbers (as a list of strings) from the "to" field
        JsonNode to = jsonNode.get("to");
        List<String> phoneNumbers = new ArrayList<>();
        if (to.isArray()) {
            for (final JsonNode objNode : to) {
                logger.info("\nPhoneNumber: ", objNode);
                phoneNumbers.add(objNode.asText());
            }
        } else {
            phoneNumbers.add(to.asText());
        }
        return phoneNumbers;
    }

    /**
     * This method sends a SMS message to a phoneNumber.
     * 
     * @param snsClient   the initialized SnsClient instance.
     * @param phoneNumber the phone number.
     * @param messageBody the message to send.
     * @return a {@code HashMap<String, String>} that contains the response status
     *         and the reponse message.
     * @throws Exception this method catches all the exeptions thrown by the
     *                   SNSClient.publish() method.
     */
    private String doSendSms(SnsClient snsClient, String phoneNumber, String messageBody) throws Exception {
        PublishRequest request = PublishRequest.builder().message(messageBody).phoneNumber(phoneNumber).build();
        PublishResponse response = snsClient.publish(request);
        String message = String.format("SMS sent to %s: %s.", phoneNumber, response.messageId());
        logger.info(message);
        return message;
    }
}
