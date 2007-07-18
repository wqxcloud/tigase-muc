/*
 * Tigase Jabber/XMPP Multi User Chatroom Component
 * Copyright (C) 2007 "Bartosz M. Małkowski" <bartosz.malkowski@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author: $
 * $Date: $
 */
package tigase.muc.xmpp.stanzas;

import tigase.muc.xmpp.JID;
import tigase.xml.Element;

/**
 * Implementation of Message stanza.
 * <p>
 * Created: 2005-01-27 20:06:44
 * </p>
 * 
 * @author bmalkow
 * @version $Rev$
 */
public class Message extends AbstractStanza<MessageType> {
    /**
     * Construct message stanza.
     * 
     * @param to
     *            recipent JID
     * @param body
     *            message content
     */
    public Message(final JID to, final String body) {
        super("message");
        setTo(to);
        setBody(body);
    }

    /**
     * Construct message stanza.
     * 
     * @param packet
     *            XML packet
     */
    public Message(final Element packet) {
        super(packet);
    }

    /**
     * Return message content.
     * 
     * @return message content
     */
    public String getBody() {
        Element sub = getChild("body");
        return sub == null ? null : sub.getCData();
    }

    /**
     * Return subject of message.
     * 
     * @return subject
     */
    public String getSubject() {
        Element sub = getChild("subject");
        return sub == null ? null : sub.getCData();
    }

    /**
     * Return a thread identifier.
     * 
     * @return thread identifier
     */
    public String getThread() {
        Element sub = getChild("thread");
        return sub == null ? null : sub.getCData();
    }

    /** {@inheritDoc} */
    public MessageType getType() {
        String type = getAttribute("type") != null ? getAttribute("type").toUpperCase() : null;
        try {
            return type == null ? null : MessageType.valueOf(type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Set message content.
     * 
     * @param body
     *            content
     */
    public void setBody(final String body) {
        if (body == null) {
            Element toDel = getChild("body");
            removeChild(toDel);
        } else {
            Element sub = getChild("body");
            if (sub == null) {
                sub = new Element("body");
                addChild(sub);
            }
            sub.setCData(body);
        }
    }

    /**
     * Set message sobject.
     * 
     * @param subject
     *            mesage subject.
     */
    public void setSubject(final String subject) {
        if (subject == null) {
            Element toDel = getChild("subject");
            removeChild(toDel);
        } else {
            Element sub = getChild("subject");
            if (sub == null) {
                sub = new Element("subject");
                addChild(sub);
            }
            sub.setCData(subject);
        }
    }

    /**
     * Set thread identifier.
     * 
     * @param thread
     *            identifier
     */
    public void setThread(final String thread) {
        if (thread == null) {
            Element toDel = getChild("thread");
            removeChild(toDel);
        } else {
            Element sub = getChild("thread");
            if (sub == null) {
                sub = new Element("thread");
                addChild(sub);
            }
            sub.setCData(thread);
        }
    }
}