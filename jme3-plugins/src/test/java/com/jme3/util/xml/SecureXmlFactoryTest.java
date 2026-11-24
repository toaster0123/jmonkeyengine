/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.util.xml;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureXmlFactoryTest {

    private static final String EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities";
    private static final String LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler";

    @Test
    void desktopReaderKeepsSecureFeaturesAndParsesBenignXml() throws Exception {
        XMLReader reader = SecureXmlFactory.createSaxReader();
        assertTrue(reader.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING));
        assertTrue(reader.getFeature("http://apache.org/xml/features/disallow-doctype-decl"));
        assertFalse(reader.getFeature(EXTERNAL_GENERAL));
        assertFalse(reader.getFeature(EXTERNAL_PARAMETER));
        assertFalse(reader.getFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd"));
        assertBenignXml(reader);
    }

    @Test
    void androidAlternativeInstallsAllGuardsAndParsesBenignXml() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        SecureXmlFactory.configureAndroidExpat(reader);
        assertFalse(reader.getFeature(EXTERNAL_GENERAL));
        assertFalse(reader.getFeature(EXTERNAL_PARAMETER));
        LexicalHandler guard = (LexicalHandler) reader.getProperty(LEXICAL_HANDLER);
        assertThrows(SAXException.class, () -> guard.startDTD("mesh", null, null));
        assertThrows(SAXException.class, () -> reader.getEntityResolver().resolveEntity(null, null));
        assertBenignXml(reader);
    }

    @Test
    void androidAlternativeFailsClosedIfLexicalHandlerIsUnavailable() throws Exception {
        XMLReader reader = new XMLFilterImpl(SAXParserFactory.newInstance().newSAXParser().getXMLReader()) {
            @Override
            public void setProperty(String name, Object value) throws SAXNotRecognizedException {
                throw new SAXNotRecognizedException(name);
            }
        };
        assertThrows(SAXNotRecognizedException.class, () -> SecureXmlFactory.configureAndroidExpat(reader));
    }

    private static void assertBenignXml(XMLReader reader) throws Exception {
        StringBuilder names = new StringBuilder();
        reader.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                names.append(uri).append(':').append(localName).append(' ');
            }
        });
        reader.parse(new InputSource(new StringReader("<mesh xmlns=\"urn:jme\"><part/></mesh>")));
        assertEquals("urn:jme:mesh urn:jme:part ", names.toString());
    }
}
