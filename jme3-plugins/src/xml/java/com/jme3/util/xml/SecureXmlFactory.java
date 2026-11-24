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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Factory methods for XML parsers that must not resolve external entities. */
public final class SecureXmlFactory {

    private static final String ANDROID_EXPAT_READER = "org.apache.harmony.xml.ExpatReader";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    private static final String LEXICAL_HANDLER_PROPERTY = "http://xml.org/sax/properties/lexical-handler";

    private SecureXmlFactory() {
    }

    /**
     * Creates a hardened SAX reader for assets. Use the reader's own parse method
     * after installing content and error handlers, so that the security handlers
     * are not replaced by the convenience overloads on SAXParser.
     */
    public static XMLReader createSaxReader() throws ParserConfigurationException, SAXException {
        // Android's platform Expat does not implement the JAXP secure-processing
        // flag or Xerces' DTD features. Keep the original requirements for
        // desktop and any other provider, even if a feature is unsupported.
        if ("Dalvik".equals(System.getProperty("java.vm.name"))) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            if (ANDROID_EXPAT_READER.equals(reader.getClass().getName())) {
                configureAndroidExpat(reader);
                return reader;
            }
        }
        return createSaxParserFactory().newSAXParser().getXMLReader();
    }

    static void configureAndroidExpat(XMLReader reader) throws SAXException {
        // Disable external entity resolution before installing a deny-by-default
        // resolver and rejecting all DTDs at the startDTD hook. Do not return a
        // usable reader if any guard cannot be installed.
        reader.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        reader.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        if (reader.getFeature(EXTERNAL_GENERAL_ENTITIES)
                || reader.getFeature(EXTERNAL_PARAMETER_ENTITIES)) {
            throw new SAXException("Android XML parser did not disable external entities");
        }
        DefaultHandler2 guard = new DefaultHandler2() {
            @Override
            public void startDTD(String name, String publicId, String systemId) throws SAXException {
                throw new SAXException("DOCTYPE declarations are not allowed");
            }

            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                throw new SAXException("External XML entities are not allowed");
            }

            @Override
            public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId)
                    throws SAXException {
                return resolveEntity(publicId, systemId);
            }
        };
        reader.setProperty(LEXICAL_HANDLER_PROPERTY, guard);
        reader.setEntityResolver(guard);
        if (reader.getProperty(LEXICAL_HANDLER_PROPERTY) != guard || reader.getEntityResolver() != guard) {
            throw new SAXException("Android XML parser did not install security handlers");
        }
    }

    public static SAXParserFactory createSaxParserFactory()
            throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        setSaxFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setSaxFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setSaxFeature(factory, EXTERNAL_GENERAL_ENTITIES, false);
        setSaxFeature(factory, EXTERNAL_PARAMETER_ENTITIES, false);
        setSaxFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    public static DocumentBuilderFactory createDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setDocumentFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setDocumentFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setDocumentFeature(factory, EXTERNAL_GENERAL_ENTITIES, false);
        setDocumentFeature(factory, EXTERNAL_PARAMETER_ENTITIES, false);
        setDocumentFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void setSaxFeature(SAXParserFactory factory, String feature, boolean value)
            throws ParserConfigurationException, SAXException {
        factory.setFeature(feature, value);
    }

    private static void setDocumentFeature(DocumentBuilderFactory factory, String feature, boolean value)
            throws ParserConfigurationException {
        factory.setFeature(feature, value);
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name, String value)
            throws ParserConfigurationException {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException e) {
            ParserConfigurationException pce = new ParserConfigurationException(
                    "XML parser does not support secure attribute: " + name);
            pce.initCause(e);
            throw pce;
        }
    }
}
