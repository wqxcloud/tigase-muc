/*
 * Tigase Jabber/XMPP Multi-User Chat Component
 * Copyright (C) 2008 "Bartosz M. Małkowski" <bartosz.malkowski@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.muc;

import tigase.xmpp.BareJID;
import tigase.xmpp.JID;

import tigase.component.exceptions.RepositoryException;
import tigase.util.TigaseStringprepException;
import tigase.xml.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author bmalkow
 * 
 */
public class Room implements RoomConfig.RoomConfigListener {

	private static class OccupantEntry {

		public BareJID jid;

		private final Set<JID> jids = new HashSet<JID>();

		private String nickname;

		private Role role = Role.none;

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			return "[" + nickname + "; " + role + "; " + jid + "; " + jids.toString() + "]";
		}
	}

	public static interface RoomFactory {

		public Room newInstance(RoomConfig rc, Date creationDate, BareJID creatorJid);

	}

	public static interface RoomListener {
		void onChangeSubject(Room room, String nick, String newSubject, Date changeDate);

		void onMessageToOccupants(Room room, JID from, Element[] contents);

		void onSetAffiliation(Room room, BareJID jid, Affiliation newAffiliation);
	}

	public static interface RoomOccupantListener {
		void onOccupantAdded(Room room, JID occupantJid);

		void onOccupantChangedPresence(Room room, JID occupantJid, String nickname, Element presence, boolean newOccupant);

		void onOccupantRemoved(Room room, JID occupantJid);
	}

	protected static RoomFactory factory = new RoomFactory() {

		@Override
		public Room newInstance(RoomConfig rc, Date creationDate, BareJID creatorJid) {
			return new Room(rc, creationDate, creatorJid);
		}

	};

	protected static final Logger log = Logger.getLogger(Room.class.getName());

	public static Room newInstance(RoomConfig rc, Date creationDate, BareJID creatorJid) {
		return factory.newInstance(rc, creationDate, creatorJid);
	}

	/**
	 * <bareJID, Affiliation>
	 */
	private final Map<BareJID, Affiliation> affiliations = new ConcurrentHashMap<BareJID, Affiliation>();

	private final RoomConfig config;

	private final Date creationDate;

	private final BareJID creatorJid;

	private final List<RoomListener> listeners = new CopyOnWriteArrayList<RoomListener>();

	private final List<RoomOccupantListener> occupantListeners = new CopyOnWriteArrayList<RoomOccupantListener>();

	/**
	 * < nickname,real JID>
	 */
	private final Map<String, OccupantEntry> occupants = new ConcurrentHashMap<String, Room.OccupantEntry>();

	protected final PresenceStore presences = new PresenceStore();

	protected final PresenceFiltered presenceFiltered;

	private final Map<String, Object> roomCustomData = new ConcurrentHashMap<String, Object>();

	private boolean roomLocked;

	private String subject;

	private Date subjectChangeDate;

	private String subjectChangerNick;

	public static final String FILTERED_OCCUPANTS_COLLECTION = "filtered_occupants_collection";

	/**
	 * @param rc
	 * @param creationDate
	 * @param creatorJid2
	 */
	protected Room(RoomConfig rc, Date creationDate, BareJID creatorJid) {
		this.config = rc;
		this.creationDate = creationDate;
		this.creatorJid = creatorJid;
		this.presenceFiltered = new PresenceFiltered(this);
		addOccupantListener( presenceFiltered );
		addListener( presenceFiltered );
		rc.addListener( this);
		presences.setOrdening( rc.getPresenceDeliveryLogic() ) ;
	}

	/**
	 * @param jid
	 * @param owner
	 * @throws RepositoryException
	 */
	public void addAffiliationByJid(BareJID jid, Affiliation affiliation) throws RepositoryException {
		if (affiliation == Affiliation.none) {
			this.affiliations.remove(jid);
		} else {
			this.affiliations.put(jid, affiliation);
		}
		fireOnSetAffiliation(jid, affiliation);
	}

	public void addListener(RoomListener listener) {
		this.listeners.add(listener);
	}

	/**
	 * @param senderJid
	 * @param nickName
	 * @param pe
	 * @throws TigaseStringprepException
	 */
	public void addOccupantByJid(JID senderJid, String nickName, Role role, Element pe) throws TigaseStringprepException {
		OccupantEntry entry = this.occupants.get(nickName);
		this.presences.update(pe);
		if (entry == null) {
			entry = new OccupantEntry();
			entry.nickname = nickName;
			entry.jid = senderJid.getBareJID();
			this.occupants.put(nickName, entry);

			if ( log.isLoggable( Level.FINEST ) ){
				log.log( Level.FINEST, "Room " + config.getRoomJID() + ". Created OccupantEntry for " + senderJid + ", nickname=" + nickName );
			}
		}

		entry.role = role;
		boolean added = false;
		synchronized (entry.jids) {
			added = entry.jids.add(senderJid);
		}

		if ( log.isLoggable( Level.FINEST ) ){
			log.log( Level.FINEST, "Room " + config.getRoomJID() + ". " + ( added ? "Added" : "Updated" ) + " occupant " + senderJid + " ("
														 + nickName + ") to room with role=" + role + "; filtering enabled: " + config.isPresenceFilterEnabled() );
		}

		if ( added ){
			if ( !config.isPresenceFilterEnabled()
					 || ( config.isPresenceFilterEnabled()
								&& ( !config.getPresenceFilteredAffiliations().isEmpty()
										 && config.getPresenceFilteredAffiliations().contains( getAffiliation( senderJid.getBareJID() ) ) ) ) ){
				fireOnOccupantAdded( senderJid );
				fireOnOccupantChangedPresence( senderJid, nickName, pe, true );
			}
		}
	}

	public void addOccupantListener(RoomOccupantListener listener) {
		this.occupantListeners.add(listener);
	}

	/**
	 * @param senderJid
	 * @param nickName
	 */
	public void changeNickName(JID senderJid, String nickName) {
		OccupantEntry occ = getBySenderJid(senderJid);
		String oldNickname = occ.nickname;

		this.occupants.remove(oldNickname);
		occ.nickname = nickName;
		this.occupants.put(nickName, occ);

		if (log.isLoggable(Level.FINEST)) {
			log.finest("Room " + config.getRoomJID() + ". Occupant " + senderJid + " changed nickname from " + oldNickname
					+ " to " + nickName);
		}
	}

	public void fireOnMessageToOccupants(JID fromJID, Element[] content) {
		for (RoomListener listener : this.listeners) {
			listener.onMessageToOccupants(this, fromJID, content);
		}
	}

	private void fireOnOccupantAdded(JID occupantJid) {
		for (RoomOccupantListener listener : this.occupantListeners) {
			listener.onOccupantAdded(this, occupantJid);
		}
	}

	private void fireOnOccupantChangedPresence(JID occupantJid, String nickname, Element cp, boolean newOccupant) {
		for (RoomOccupantListener listener : this.occupantListeners) {
			listener.onOccupantChangedPresence(this, occupantJid, nickname, cp, newOccupant);
		}
	}

	private void fireOnOccupantRemoved(JID occupantJid) {
		for (RoomOccupantListener listener : this.occupantListeners) {
			listener.onOccupantRemoved(this, occupantJid);
		}
	}

	private void fireOnSetAffiliation(BareJID jid, Affiliation affiliation) {
		for (RoomListener listener : this.listeners) {
			listener.onSetAffiliation(this, jid, affiliation);
		}
	}

	private void fireOnSetSubject(String nick, String subject, Date changeDate) {
		for (RoomListener listener : this.listeners) {
			listener.onChangeSubject(this, nick, subject, changeDate);
		}
	}

	/**
	 * @param value
	 *            user JID
	 * @return
	 */
	public Affiliation getAffiliation(BareJID jid) {
		Affiliation result = null;
		if (jid != null) {
			result = this.affiliations.get(jid);
		}
		return result == null ? Affiliation.none : result;
	}

	/**
	 * @param occupantNickname
	 * @return
	 */
	public Affiliation getAffiliation(String nickname) {
		OccupantEntry entry = this.occupants.get(nickname);
		return getAffiliation(entry == null ? null : entry.jid);
	}

	/**
	 * @return
	 */
	public Collection<BareJID> getAffiliations() {
		return this.affiliations.keySet();
	}

	/**
	 * 
	 */
	public Collection<JID> getAllOccupantsJID() {
		if ( config.isPresenceFilterEnabled() ){
			return presenceFiltered.getOccupantsPresenceFilteredJIDs();
		} else {
			return presences.getAllKnownJIDs();
		}
	}

	private OccupantEntry getBySenderJid(JID sender) {
		for (Entry<String, OccupantEntry> e : occupants.entrySet()) {
			synchronized (e.getValue().jids) {
				if (e.getValue().jids.contains(sender)) {
					return e.getValue();
				}
			}
		}
		return null;
	}

	public RoomConfig getConfig() {
		return config;
	}

	/**
	 * @return
	 */
	public Date getCreationDate() {
		return this.creationDate;
	}

	public BareJID getCreatorJid() {
		return creatorJid;
	}

	/**
	 * @return
	 */
	public String getDebugInfoOccupants() {
		StringBuilder sb = new StringBuilder();
		sb.append("Occupants in room " + config.getRoomJID() + "[" + occupants.entrySet().size() +"]: ");
		for (Entry<String, OccupantEntry> o : occupants.entrySet()) {
			sb.append(o.getKey()).append('=').append(o.getValue().toString()).append(" ");
		}
		return sb.toString();
	}

	public Element getLastPresenceCopy(BareJID occupantJid, String nickname) {
		return getLastPresenceCopyByJid( occupantJid );
	}

	public Element getLastPresenceCopyByJid(BareJID occupantJid) {
		Element e = this.presences.getBestPresence(occupantJid);
		if (e != null) {
			return e.clone();
		} else {
			return null;
		}
	}

	/**
	 * @return
	 */
	public int getOccupantsCount() {
		return this.occupants.size();
	}

	/**
	 * @param occupantNickname
	 * @return
	 */
	public BareJID getOccupantsJidByNickname(String nickname) {
		OccupantEntry entry = this.occupants.get(nickname);
		if (entry == null)
			return null;

		synchronized (entry.jids) {
			if (!entry.jids.isEmpty()) {
				return entry.jids.iterator().next().getBareJID();
			}
		}
		return null;
	}

	/**
	 * @param recipientNickame
	 * @return
	 */
	public Collection<JID> getOccupantsJidsByNickname(final String nickname) {
		OccupantEntry entry = this.occupants.get(nickname);
		if (entry == null)
			return new ArrayList<JID>();

		return Collections.unmodifiableCollection(new ConcurrentSkipListSet(entry.jids));
	}

	/**
	 * @param jid
	 * @return
	 */
	public String getOccupantsNickname(JID jid) {
		OccupantEntry e = getBySenderJid(jid);
		if (e == null)
			return null;

		String nickname = e.nickname;

		return nickname;
	}

	/**
	 * @return
	 */
	public Collection<String> getOccupantsNicknames() {
		return Collections.unmodifiableCollection(new ConcurrentSkipListSet(this.occupants.keySet()));
	}

	/**
	 * @param occupantBareJid
	 * @return
	 */
	public Collection<String> getOccupantsNicknames(BareJID bareJid) {
		Set<String> result = new HashSet<String>();

		for (Entry<String, OccupantEntry> e : this.occupants.entrySet()) {
			if (e.getValue().jid.equals(bareJid)) {
				result.add(e.getKey());
			}
		}

		return Collections.unmodifiableCollection(new ConcurrentSkipListSet(result));
	}

	public PresenceFiltered getPresenceFiltered() {
		return presenceFiltered;
	}

	/**
	 * @param nickName
	 * @return
	 */
	public Role getRole(String nickname) {
		if (nickname == null)
			return Role.none;
		OccupantEntry entry = this.occupants.get(nickname);
		if (entry == null)
			return Role.none;
		return entry.role == null ? Role.none : entry.role;
	}

	public Object getRoomCustomData(String key) {
		return roomCustomData.get(key);
	}

	public BareJID getRoomJID() {
		return this.config.getRoomJID();
	}

	/**
	 * @return
	 */
	public String getSubject() {
		return subject;
	}

	public Date getSubjectChangeDate() {
		return subjectChangeDate;
	}

	/**
	 * @return
	 */
	public String getSubjectChangerNick() {
		return subjectChangerNick;
	}

	/**
	 * @param senderJID
	 * @return
	 */
	public boolean isOccupantInRoom(final JID jid) {
		return getBySenderJid(jid) != null;
	}

	public boolean isRoomLocked() {
		return roomLocked;
	}

	@Override
	public void onConfigChanged( RoomConfig roomConfig, Set<String> modifiedVars ) {
		presences.setOrdening( roomConfig.getPresenceDeliveryLogic());
	}

	public void removeListener(RoomListener listener) {
		this.listeners.remove(listener);
	}

	/**
	 * 
	 * @param jid
	 * @return <code>true</code> if no more JIDs assigned to nickname. In other
	 *         words: nickname is removed
	 */
	public boolean removeOccupant(JID jid) {
		OccupantEntry e = getBySenderJid(jid);
		if (e != null) {
			try {
				synchronized (e.jids) {
					e.jids.remove(jid);
					if (log.isLoggable(Level.FINEST)) {
						log.finest("Room " + config.getRoomJID() + ". Removed JID " + jid + " of occupant");
					}
					if (e.jids.isEmpty()) {
						this.occupants.remove(e.nickname);
						if (log.isLoggable(Level.FINEST)) {
							log.finest("Room " + config.getRoomJID() + ". Removed occupant " + jid);
						}
						return true;
					}
				}
			} finally {
				fireOnOccupantRemoved(jid);
			}
		}
		return false;
	}

	/**
	 * @param occupantNick
	 */
	public void removeOccupant(String occupantNick) {
		OccupantEntry e = this.occupants.remove(occupantNick);
		if (e != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Room " + config.getRoomJID() + ". Removed occupant " + occupantNick);
			}

			for (JID jid : e.jids) {
				fireOnOccupantRemoved(jid);
			}
		}
	}

	/**
	 * @param affiliations2
	 */
	public void setAffiliations(Map<BareJID, Affiliation> affiliations) {
		this.affiliations.clear();
		this.affiliations.putAll(affiliations);
	}

	/**
	 * @param occupantNick
	 * @param newRole
	 */
	public void setNewRole(String nickname, Role newRole) {
		OccupantEntry entry = this.occupants.get(nickname);
		if (entry != null) {
			entry.role = newRole;
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Room " + config.getRoomJID() + ". Changed role of occupant " + nickname + " to " + newRole);
			}

		}

	}

	/**
	 * @param msg
	 * @param senderRoomJid
	 * @throws RepositoryException
	 */
	public void setNewSubject(String msg, String senderNickname) throws RepositoryException {
		this.subjectChangerNick = senderNickname;
		this.subject = msg;
		this.subjectChangeDate = new Date();
		fireOnSetSubject(senderNickname, msg, this.subjectChangeDate);
	}

	public void setRoomCustomData(String key, Object data) {
		synchronized (this.roomCustomData) {
			this.roomCustomData.put(key, data);
		}
	}

	public void setRoomLocked(boolean roomLocked) {
		this.roomLocked = roomLocked;
	}

	public void setSubjectChangeDate(Date subjectChangeDate) {
		this.subjectChangeDate = subjectChangeDate;
	}

	/**
	 * @param nickName
	 * @param element
	 * @throws TigaseStringprepException
	 */
	public void updatePresenceByJid(JID jid, String nickname, Element cp) throws TigaseStringprepException {
		if (cp == null) {
			this.presences.remove(jid);
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Room " + config.getRoomJID() + ". Removed presence from " + jid + " (" + nickname + ")");
			}
		} else {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Room " + config.getRoomJID() + ". Updated presence from " + jid + " (" + nickname + ")");
			}
			this.presences.update(cp);
		}

		fireOnOccupantChangedPresence(jid, nickname, cp, false);
	}
}
