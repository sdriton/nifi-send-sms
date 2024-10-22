package com.driton.nifi.sms;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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

@Tags({"aws", "sns", "sms", "notification", "amazon"})
@CapabilityDescription("Sends an SMS message to each phone number retrieved from the incoming Flowfile using AWS SNS. "
		+ "The incoming Flowfile has to be in a json format."
		+ "/nExample: {\"to\": [\"+15143334444\",\"+15143334445\"], \"body\": \"SMS Message\"}")
@WritesAttributes({
    @WritesAttribute(attribute = "aws.sms.status", description = "Status of the SMS message sent through AWS SNS"),
    @WritesAttribute(attribute = "aws.sms.error", description = "Error details if the SMS failed to send")
})
public class PutSmsProcessor extends AbstractProcessor {

    public static final PropertyDescriptor AWS_ACCESS_KEY = new PropertyDescriptor.Builder()
            .name("AWS Access Key")
            .description("AWS Access Key for SNS")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .sensitive(true)
            .build();

    public static final PropertyDescriptor AWS_SECRET_KEY = new PropertyDescriptor.Builder()
            .name("AWS Secret Key")
            .description("AWS Secret Key for SNS")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .sensitive(true)
            .build();

    public static final PropertyDescriptor AWS_REGION = new PropertyDescriptor.Builder()
            .name("AWS Region")
            .description("The AWS region to use")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .defaultValue("us-east-1")
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All FlowFiles that successfully send an SMS are routed to this relationship")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("FlowFiles that fail to send an SMS are routed to this relationship")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(AWS_ACCESS_KEY);
        descriptors.add(AWS_SECRET_KEY);
        descriptors.add(AWS_REGION);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = Set.of(REL_SUCCESS, REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
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

        final ComponentLog logger = getLogger();
        final String accessKey = context.getProperty(AWS_ACCESS_KEY).getValue();
        final String secretKey = context.getProperty(AWS_SECRET_KEY).getValue();
        final String region = context.getProperty(AWS_REGION).getValue();

        SnsClient snsClient = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        // Use an array to hold the flowFile, to work around final/effectively final issue
        final FlowFile[] flowFileHolder = new FlowFile[] { flowFile };
        
        // Read JSON content from the incoming flow file
        try (InputStream inputStream = session.read(flowFile)) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(inputStream);

            // Extract phone numbers (as a list of strings) from the "to" field
            String toField = jsonNode.get("to").asText();
            List<String> phoneNumbers = objectMapper.readValue(toField, List.class);

            // Extract the message body from the "body" field
            String messageBody = jsonNode.get("body").asText();

            // Send SMS to each phone number
            for (String phoneNumber : phoneNumbers) {
                try {
                    PublishRequest request = PublishRequest.builder()
                            .message(messageBody)
                            .phoneNumber(phoneNumber)
                            .build();

                    PublishResponse response = snsClient.publish(request);
                    logger.info("SMS sent to {}: {}", phoneNumber, response.messageId());
                    flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.status", "SMS sent to " + phoneNumber);
                } catch (Exception e) {
                    logger.error("Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
                    flowFileHolder[0] = session.putAttribute(flowFileHolder[0], "aws.sms.error", "Failed to send SMS to " + phoneNumber + ": " + e.getMessage());
                    session.transfer(flowFileHolder[0], REL_FAILURE);
                    return;
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
}
