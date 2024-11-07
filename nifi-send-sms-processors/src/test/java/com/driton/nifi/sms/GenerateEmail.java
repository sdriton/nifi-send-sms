/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.driton.nifi.sms;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class GenerateEmail {
    String from;
    String to;
    String subject;
    String message;
    String hostName;

    public GenerateEmail(){
        this(
            "sender@ethereal.email", 
            "+11239994444@ethereal.email", 
            "Test email", 
            "This is my text to send as SMS", 
            "localhost");
    }

    public GenerateEmail(String from, String to, String subject, String message, String hostName) {
        this.from = from;
        this.to = to;
        this.subject = subject;
        this.message = message;
        this.hostName = hostName;
    }

    public byte[] emailMessage(final String to) {
        MimeMessage msg = this.emailMessageAsMimeMessage(to);

        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] result = null;
        try {
            msg.writeTo(byteArrayOutputStream);
            result = byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public MimeMessage emailMessageAsMimeMessage(final String to){
        Properties prop = new Properties();
        Session session = Session.getDefaultInstance(prop);
        MimeMessage msg = new MimeMessage(session);
        try {
            msg.setFrom(new InternetAddress(this.from));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(this.to));
            msg.setSubject(this.subject);
            msg.setText(this.message);
            msg.setHeader("hostname", this.hostName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return msg;
    }

    public byte[] fromFile(String fileName) {
        byte[] fileContent = null;
        try {
            Path filePath = Paths.get("src", "test", "resources", fileName);
            fileContent = Files.readAllBytes(filePath);
        } catch (Exception e) {
            // Do nothing
            System.out.println("Error reading email file. Message: " + e.getMessage());
            e.printStackTrace();
        }

        return fileContent;
    }
}
