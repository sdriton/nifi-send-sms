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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.FlowFileAccessException;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@SeeAlso({PutSms.class})
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({ "email", "extract", "convert", "json" })
@CapabilityDescription("This Processor extracts email message body and recepients (to) fields and creates a json structure as a FlowFile. "
        + "Below is provided a sample Flowfile content :"
        + "\nExample: {\"to\": [\"+15143334444\",\"+15143334445\"], \"body\": \"SMS Message\"}")
@WritesAttributes({
        @WritesAttribute(attribute = "email.conversion.status", description = "Status of the email conversion."),
        @WritesAttribute(attribute = "email.conversion.error", description = "Error details if the email fails to convert.") })
public class ExtractEmailToJson extends AbstractProcessor {
    private ComponentLog logger = null;

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
            .description("FlowFiles that succeed are routed to this relationship").build();

    public static final Relationship REL_FAILURE = new Relationship.Builder().name("failure")
            .description("FlowFiles that fail are routed to this relationship").build();

    private Set<Relationship> relationships;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final Set<Relationship> relLst = Set.of(REL_SUCCESS, REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relLst);
        this.logger = getLogger();
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            session.read(flowFile, (InputStreamCallback) inputStream -> {
                byteArrayOutputStream.write(inputStream.readAllBytes());
            });
        } catch (FlowFileAccessException ffae) {
            throw new ProcessException("Failed to read FlowFile content.", ffae);
        }

        byte[] emailContent = byteArrayOutputStream.toByteArray();

        try {

            // Parse the email content
            MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()),
                    new ByteArrayInputStream(emailContent));
            List<String> recipients = getRecipients(mimeMessage);
            String emailBody = getEmailBody(mimeMessage);

            // Create JSON content
            Map<String, Object> jsonMap = new HashMap<>();
            jsonMap.put("to", recipients);
            jsonMap.put("body", emailBody);

            // Write JSON content back to FlowFile
            FlowFile updatedFlowFile = session.write(flowFile,
                    (OutputStreamCallback) outputStream -> objectMapper.writeValue(outputStream, jsonMap));
            session.transfer(updatedFlowFile, REL_SUCCESS);

        } catch (Exception e) {
            logger.error("Error processing FlowFile: ", e.getMessage());
            FlowFile emptyFlowFile = session.write(flowFile,
                    (OutputStreamCallback) outputStream -> objectMapper.writeValue(outputStream, ""));
            session.transfer(emptyFlowFile, REL_FAILURE);
        }
    }

    /**
     * This method extracts the recipients from the "to" email field.
     * 
     * @param mimeMessage the MimeMessage of the email.
     * @return a {@code List<String>} of phone numbers in E.164 format
     *         (+1xxxyyyyyyy) retrieved from the address phone_number@domain.com
     *         (+18662224444).
     */
    private List<String> getRecipients(MimeMessage mimeMessage) throws MessagingException {
        List<String> recipients = new ArrayList<>();
        Address[] toAddresses = mimeMessage.getRecipients(Message.RecipientType.TO);

        if (toAddresses != null) {
            for (Address address : toAddresses) {
                recipients.add(((InternetAddress) address).getAddress().split("@")[0]);
            }
        }
        return recipients;
    }

    /**
     * This method extracts the email body.
     * 
     * @param mimeMessage the MimeMessage of the email.
     * @return a {@code String} representing the body of the email.
     */
    private String getEmailBody(MimeMessage mimeMessage) throws MessagingException, IOException {
        if (mimeMessage.isMimeType("text/plain")) {
            return (String) mimeMessage.getContent();
        } else if (mimeMessage.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) mimeMessage.getContent();
            return getTextFromMimeMultipart(multipart);
        }
        return "";
    }

    /**
     * This method extracts the email body from multipart content.
     * 
     * @param mimeMessage the MimeMessage of the email.
     * @return a {@code String} representing the body of the email.
     */
    private String getTextFromMimeMultipart(Multipart multipart) throws MessagingException, IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                result.append(bodyPart.getContent());
            } else if (bodyPart.isMimeType("text/html")) {
                // Ignore HTML part
            } else if (bodyPart.getContent() instanceof Multipart) {
                result.append(getTextFromMimeMultipart((Multipart) bodyPart.getContent()));
            }
        }
        return result.toString();
    }
}
