/*
 * Central Repository
 *
 * Copyright 2017-2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.centralrepository.eventlisteners;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagDeletedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagDeletedEvent;
import org.sleuthkit.autopsy.casemodule.events.DataSourceAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.DataSourceNameChangedEvent;
import org.sleuthkit.autopsy.casemodule.services.TagsManager;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeUtil;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationCase;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationDataSource;
import org.sleuthkit.autopsy.centralrepository.datamodel.CentralRepoException;
import org.sleuthkit.autopsy.coreutils.ThreadUtils;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifactTag;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.TagName;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.autopsy.centralrepository.datamodel.CentralRepository;

/**
 * Listen for case events and update entries in the Central Repository database
 * accordingly
 */
@Messages({"caseeventlistener.evidencetag=Evidence"})
final class CaseEventListener implements PropertyChangeListener {

    private static final Logger LOGGER = Logger.getLogger(CaseEventListener.class.getName());
    private final ExecutorService jobProcessingExecutor;
    private static final String CASE_EVENT_THREAD_NAME = "Case-Event-Listener-%d";
    
    private static final Set<Case.Events> CASE_EVENTS_OF_INTEREST = EnumSet.of(
            Case.Events.CONTENT_TAG_ADDED, Case.Events.CONTENT_TAG_DELETED,
            Case.Events.BLACKBOARD_ARTIFACT_TAG_DELETED, Case.Events.BLACKBOARD_ARTIFACT_TAG_ADDED,
            Case.Events.CONTENT_TAG_ADDED, Case.Events.CONTENT_TAG_DELETED,
            Case.Events.DATA_SOURCE_ADDED, 
            Case.Events.TAG_DEFINITION_CHANGED,
            Case.Events.CURRENT_CASE,
            Case.Events.DATA_SOURCE_NAME_CHANGED);

    CaseEventListener() {
        jobProcessingExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat(CASE_EVENT_THREAD_NAME).build());
    }

    void shutdown() {
        ThreadUtils.shutDownTaskExecutor(jobProcessingExecutor);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        CentralRepository dbManager;
        try {
            dbManager = CentralRepository.getInstance();
        } catch (CentralRepoException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get instance of db manager.", ex);
            return;
        }
        
        // If any changes are made to which event types are handled the change 
        // must also be made to CASE_EVENTS_OF_INTEREST.
        switch (Case.Events.valueOf(evt.getPropertyName())) {
            case CONTENT_TAG_ADDED:
            case CONTENT_TAG_DELETED: {
                jobProcessingExecutor.submit(new ContentTagTask(dbManager, evt));
            }
            break;

            case BLACKBOARD_ARTIFACT_TAG_DELETED:
            case BLACKBOARD_ARTIFACT_TAG_ADDED: {
                jobProcessingExecutor.submit(new BlackboardTagTask(dbManager, evt));
            }
            break;

            case DATA_SOURCE_ADDED: {
                jobProcessingExecutor.submit(new DataSourceAddedTask(dbManager, evt));
            }
            break;
            case TAG_DEFINITION_CHANGED: {
                jobProcessingExecutor.submit(new TagDefinitionChangeTask(evt));
            }
            break;
            case CURRENT_CASE: {
                jobProcessingExecutor.submit(new CurrentCaseTask(dbManager, evt));
            }
            break;
            case DATA_SOURCE_NAME_CHANGED: {
                jobProcessingExecutor.submit(new DataSourceNameChangedTask(dbManager, evt));
            }
            break;
        }
    }
    
    /*
     * Add all of our Case Event Listeners to the case.
     */
    void installListeners() {
        Case.addEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, this);
    }

    /*
     * Remove all of our Case Event Listeners from the case.
     */
    void uninstallListeners() {
        Case.removeEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, this);
    }

    private final class ContentTagTask implements Runnable {

        private final CentralRepository dbManager;
        private final PropertyChangeEvent event;

        private ContentTagTask(CentralRepository db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!CentralRepository.isEnabled()) {
                return;
            }

            AbstractFile af;
            TskData.FileKnown knownStatus;
            String comment;
            if (Case.Events.valueOf(event.getPropertyName()) == Case.Events.CONTENT_TAG_ADDED) {
                // For added tags, we want to change the known status to BAD if the 
                // tag that was just added is in the list of central repo tags.
                final ContentTagAddedEvent tagAddedEvent = (ContentTagAddedEvent) event;
                final ContentTag tagAdded = tagAddedEvent.getAddedTag();

                if (TagsManager.getNotableTagDisplayNames().contains(tagAdded.getName().getDisplayName())) {
                    if (tagAdded.getContent() instanceof AbstractFile) {
                        af = (AbstractFile) tagAdded.getContent();
                        knownStatus = TskData.FileKnown.BAD;
                        comment = tagAdded.getComment();
                    } else {
                        LOGGER.log(Level.WARNING, "Error updating non-file object");
                        return;
                    }
                } else {
                    // The added tag isn't flagged as bad in central repo, so do nothing
                    return;
                }
            } else { // CONTENT_TAG_DELETED
                // For deleted tags, we want to set the file status to UNKNOWN if:
                //   - The tag that was just removed is notable in central repo
                //   - There are no remaining tags that are notable 
                final ContentTagDeletedEvent tagDeletedEvent = (ContentTagDeletedEvent) event;
                long contentID = tagDeletedEvent.getDeletedTagInfo().getContentID();

                String tagName = tagDeletedEvent.getDeletedTagInfo().getName().getDisplayName();
                if (!TagsManager.getNotableTagDisplayNames().contains(tagName)) {
                    // If the tag that got removed isn't on the list of central repo tags, do nothing
                    return;
                }

                try {
                    // Get the remaining tags on the content object
                    Content content = Case.getCurrentCaseThrows().getSleuthkitCase().getContentById(contentID);
                    TagsManager tagsManager = Case.getCurrentCaseThrows().getServices().getTagsManager();
                    List<ContentTag> tags = tagsManager.getContentTagsByContent(content);

                    if (tags.stream()
                            .map(tag -> tag.getName().getDisplayName())
                            .filter(TagsManager.getNotableTagDisplayNames()::contains)
                            .collect(Collectors.toList())
                            .isEmpty()) {

                        // There are no more bad tags on the object
                        if (content instanceof AbstractFile) {
                            af = (AbstractFile) content;
                            knownStatus = TskData.FileKnown.UNKNOWN;
                            comment = "";
                        } else {
                            LOGGER.log(Level.WARNING, "Error updating non-file object");
                            return;
                        }
                    } else {
                        // There's still at least one bad tag, so leave the known status as is
                        return;
                    }
                } catch (TskCoreException | NoCurrentCaseException ex) {
                    LOGGER.log(Level.SEVERE, "Failed to find content", ex);
                    return;
                }
            }

            final CorrelationAttributeInstance eamArtifact = CorrelationAttributeUtil.makeCorrAttrFromFile(af);

            if (eamArtifact != null) {
                // send update to Central Repository db
                try {
                    dbManager.setAttributeInstanceKnownStatus(eamArtifact, knownStatus);
                } catch (CentralRepoException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database while setting artifact known status.", ex); //NON-NLS
                }
            }
        } // CONTENT_TAG_ADDED, CONTENT_TAG_DELETED
    }

    private final class BlackboardTagTask implements Runnable {

        private final CentralRepository dbManager;
        private final PropertyChangeEvent event;

        private BlackboardTagTask(CentralRepository db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!CentralRepository.isEnabled()) {
                return;
            }

            Content content;
            BlackboardArtifact bbArtifact;
            TskData.FileKnown knownStatus;
            String comment;
            if (Case.Events.valueOf(event.getPropertyName()) == Case.Events.BLACKBOARD_ARTIFACT_TAG_ADDED) {
                // For added tags, we want to change the known status to BAD if the 
                // tag that was just added is in the list of central repo tags.
                final BlackBoardArtifactTagAddedEvent tagAddedEvent = (BlackBoardArtifactTagAddedEvent) event;
                final BlackboardArtifactTag tagAdded = tagAddedEvent.getAddedTag();

                if (TagsManager.getNotableTagDisplayNames().contains(tagAdded.getName().getDisplayName())) {
                    content = tagAdded.getContent();
                    bbArtifact = tagAdded.getArtifact();
                    knownStatus = TskData.FileKnown.BAD;
                    comment = tagAdded.getComment();
                } else {
                    // The added tag isn't flagged as bad in central repo, so do nothing
                    return;
                }
            } else { //BLACKBOARD_ARTIFACT_TAG_DELETED
                Case openCase;
                try {
                    openCase = Case.getCurrentCaseThrows();
                } catch (NoCurrentCaseException ex) {
                    LOGGER.log(Level.SEVERE, "Exception while getting open case.", ex);
                    return;
                }
                // For deleted tags, we want to set the file status to UNKNOWN if:
                //   - The tag that was just removed is notable in central repo
                //   - There are no remaining tags that are notable 
                final BlackBoardArtifactTagDeletedEvent tagDeletedEvent = (BlackBoardArtifactTagDeletedEvent) event;
                long contentID = tagDeletedEvent.getDeletedTagInfo().getContentID();
                long artifactID = tagDeletedEvent.getDeletedTagInfo().getArtifactID();

                String tagName = tagDeletedEvent.getDeletedTagInfo().getName().getDisplayName();
                if (!TagsManager.getNotableTagDisplayNames().contains(tagName)) {
                    // If the tag that got removed isn't on the list of central repo tags, do nothing
                    return;
                }

                try {
                    // Get the remaining tags on the artifact
                    content = openCase.getSleuthkitCase().getContentById(contentID);
                    bbArtifact = openCase.getSleuthkitCase().getBlackboardArtifact(artifactID);
                    TagsManager tagsManager = openCase.getServices().getTagsManager();
                    List<BlackboardArtifactTag> tags = tagsManager.getBlackboardArtifactTagsByArtifact(bbArtifact);

                    if (tags.stream()
                            .map(tag -> tag.getName().getDisplayName())
                            .filter(TagsManager.getNotableTagDisplayNames()::contains)
                            .collect(Collectors.toList())
                            .isEmpty()) {

                        // There are no more bad tags on the object
                        knownStatus = TskData.FileKnown.UNKNOWN;
                        comment = "";

                    } else {
                        // There's still at least one bad tag, so leave the known status as is
                        return;
                    }
                } catch (TskCoreException ex) {
                    LOGGER.log(Level.SEVERE, "Failed to find content", ex);
                    return;
                }
            }

            if ((content instanceof AbstractFile) && (((AbstractFile) content).getKnown() == TskData.FileKnown.KNOWN)) {
                return;
            }

            List<CorrelationAttributeInstance> convertedArtifacts = CorrelationAttributeUtil.makeCorrAttrsForCorrelation(bbArtifact);
            for (CorrelationAttributeInstance eamArtifact : convertedArtifacts) {
                eamArtifact.setComment(comment);
                try {
                    dbManager.setAttributeInstanceKnownStatus(eamArtifact, knownStatus);
                } catch (CentralRepoException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database while setting artifact known status.", ex); //NON-NLS
                }
            }
        } // BLACKBOARD_ARTIFACT_TAG_ADDED, BLACKBOARD_ARTIFACT_TAG_DELETED

    }

    private final class TagDefinitionChangeTask implements Runnable {

        private final PropertyChangeEvent event;

        private TagDefinitionChangeTask(PropertyChangeEvent evt) {
            event = evt;
        }

        @Override
        public void run() {
            if (!CentralRepository.isEnabled()) {
                return;
            }
            //get the display name of the tag that has had it's definition modified
            String modifiedTagName = (String) event.getOldValue();

            /*
             * Set knownBad status for all files/artifacts in the given case
             * that are tagged with the given tag name.
             */
            try {
                TagName tagName = Case.getCurrentCaseThrows().getServices().getTagsManager().getDisplayNamesToTagNamesMap().get(modifiedTagName);
                //First update the artifacts
                //Get all BlackboardArtifactTags with this tag name
                List<BlackboardArtifactTag> artifactTags = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboardArtifactTagsByTagName(tagName);
                for (BlackboardArtifactTag bbTag : artifactTags) {
                    //start with assumption that none of the other tags applied to this Correlation Attribute will prevent it's status from being changed
                    boolean hasTagWithConflictingKnownStatus = false;
                    // if the status of the tag has been changed to TskData.FileKnown.UNKNOWN
                    // we need to check the status of all other tags on this correlation attribute before changing
                    // the status of the correlation attribute in the central repository
                    if (tagName.getKnownStatus() == TskData.FileKnown.UNKNOWN) {
                        Content content = bbTag.getContent();
                        // If the content which this Blackboard Artifact Tag is linked to is an AbstractFile with KNOWN status then 
                        // it's status in the central reporsitory should not be changed to UNKNOWN
                        if ((content instanceof AbstractFile) && (((AbstractFile) content).getKnown() == TskData.FileKnown.KNOWN)) {
                            continue;
                        }
                        //Get the BlackboardArtifact which this BlackboardArtifactTag has been applied to.
                        BlackboardArtifact bbArtifact = bbTag.getArtifact();
                        TagsManager tagsManager = Case.getCurrentCaseThrows().getServices().getTagsManager();
                        List<BlackboardArtifactTag> tags = tagsManager.getBlackboardArtifactTagsByArtifact(bbArtifact);
                        //get all tags which are on this blackboard artifact
                        for (BlackboardArtifactTag t : tags) {
                            //All instances of the modified tag name will be changed, they can not conflict with each other
                            if (t.getName().equals(tagName)) {
                                continue;
                            }
                            //if any other tags on this artifact are Notable in status then this artifact can not have its status changed 
                            if (TskData.FileKnown.BAD == t.getName().getKnownStatus()) {
                                //a tag with a conflicting status has been found, the status of this correlation attribute can not be modified
                                hasTagWithConflictingKnownStatus = true;
                                break;
                            }
                        }
                    }
                    //if the Correlation Attribute will have no tags with a status which would prevent the current status from being changed 
                    if (!hasTagWithConflictingKnownStatus) {
                        //Get the correlation atttributes that correspond to the current BlackboardArtifactTag if their status should be changed
                        //with the initial set of correlation attributes this should be a single correlation attribute
                        List<CorrelationAttributeInstance> convertedArtifacts = CorrelationAttributeUtil.makeCorrAttrsForCorrelation(bbTag.getArtifact());
                        for (CorrelationAttributeInstance eamArtifact : convertedArtifacts) {
                            CentralRepository.getInstance().setAttributeInstanceKnownStatus(eamArtifact, tagName.getKnownStatus());
                        }
                    }
                }
                // Next update the files

                List<ContentTag> fileTags = Case.getCurrentCaseThrows().getSleuthkitCase().getContentTagsByTagName(tagName);
                //Get all ContentTags with this tag name
                for (ContentTag contentTag : fileTags) {
                    //start with assumption that none of the other tags applied to this ContentTag will prevent it's status from being changed
                    boolean hasTagWithConflictingKnownStatus = false;
                    // if the status of the tag has been changed to TskData.FileKnown.UNKNOWN
                    // we need to check the status of all other tags on this file before changing
                    // the status of the file in the central repository
                    if (tagName.getKnownStatus() == TskData.FileKnown.UNKNOWN) {
                        Content content = contentTag.getContent();
                        TagsManager tagsManager = Case.getCurrentCaseThrows().getServices().getTagsManager();
                        List<ContentTag> tags = tagsManager.getContentTagsByContent(content);
                        //get all tags which are on this file
                        for (ContentTag t : tags) {
                            //All instances of the modified tag name will be changed, they can not conflict with each other
                            if (t.getName().equals(tagName)) {
                                continue;
                            }
                            //if any other tags on this file are Notable in status then this file can not have its status changed 
                            if (TskData.FileKnown.BAD == t.getName().getKnownStatus()) {
                                //a tag with a conflicting status has been found, the status of this file can not be modified
                                hasTagWithConflictingKnownStatus = true;
                                break;
                            }
                        }
                    }
                    //if the file will have no tags with a status which would prevent the current status from being changed 
                    if (!hasTagWithConflictingKnownStatus) {
                        Content taggedContent = contentTag.getContent();
                        if (taggedContent instanceof AbstractFile) {                            
                            final CorrelationAttributeInstance eamArtifact = CorrelationAttributeUtil.makeCorrAttrFromFile((AbstractFile)taggedContent);
                            if (eamArtifact != null) {
                                CentralRepository.getInstance().setAttributeInstanceKnownStatus(eamArtifact, tagName.getKnownStatus());
                            }
                        }
                    }
                }
            } catch (TskCoreException ex) {
                LOGGER.log(Level.SEVERE, "Cannot update known status in central repository for tag: " + modifiedTagName, ex);  //NON-NLS
            } catch (CentralRepoException ex) {
                LOGGER.log(Level.SEVERE, "Cannot get central repository for tag: " + modifiedTagName, ex);  //NON-NLS
            } catch (NoCurrentCaseException ex) {
                LOGGER.log(Level.SEVERE, "Exception while getting open case.", ex);  //NON-NLS
            }
        } //TAG_STATUS_CHANGED
    }

    private final class DataSourceAddedTask implements Runnable {

        private final CentralRepository dbManager;
        private final PropertyChangeEvent event;

        private DataSourceAddedTask(CentralRepository db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!CentralRepository.isEnabled()) {
                return;
            }
            Case openCase;
            try {
                openCase = Case.getCurrentCaseThrows();
            } catch (NoCurrentCaseException ex) {
                LOGGER.log(Level.SEVERE, "Exception while getting open case.", ex);
                return;
            }

            final DataSourceAddedEvent dataSourceAddedEvent = (DataSourceAddedEvent) event;
            Content newDataSource = dataSourceAddedEvent.getDataSource();

            try {
                CorrelationCase correlationCase = dbManager.getCase(openCase);
                if (null == dbManager.getDataSource(correlationCase, newDataSource.getId())) {
                    CorrelationDataSource.fromTSKDataSource(correlationCase, newDataSource);
                }
            } catch (CentralRepoException ex) {
                LOGGER.log(Level.SEVERE, "Error adding new data source to the central repository", ex); //NON-NLS
            }
        } // DATA_SOURCE_ADDED
    }

    private final class CurrentCaseTask implements Runnable {

        private final CentralRepository dbManager;
        private final PropertyChangeEvent event;

        private CurrentCaseTask(CentralRepository db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            /*
             * A case has been opened if evt.getOldValue() is null and
             * evt.getNewValue() is a valid Case.
             */
            if ((null == event.getOldValue()) && (event.getNewValue() instanceof Case)) {
                Case curCase = (Case) event.getNewValue();
                IngestEventsListener.resetCeModuleInstanceCount();

                if (!CentralRepository.isEnabled()) {
                    return;
                }

                try {
                    // NOTE: Cannot determine if the opened case is a new case or a reopened case,
                    //  so check for existing name in DB and insert if missing.
                    if (dbManager.getCase(curCase) == null) {
                        dbManager.newCase(curCase);
                    }
                } catch (CentralRepoException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database.", ex); //NON-NLS
                }
            }
        } // CURRENT_CASE
    }

    private final class DataSourceNameChangedTask implements Runnable {

        private final CentralRepository dbManager;
        private final PropertyChangeEvent event;

        private DataSourceNameChangedTask(CentralRepository db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {

            final DataSourceNameChangedEvent dataSourceNameChangedEvent = (DataSourceNameChangedEvent) event;
            Content dataSource = dataSourceNameChangedEvent.getDataSource();
            String newName = (String) event.getNewValue();

            if (!StringUtils.isEmpty(newName)) {

                if (!CentralRepository.isEnabled()) {
                    return;
                }

                try {
                    CorrelationCase correlationCase = dbManager.getCase(Case.getCurrentCaseThrows());
                    CorrelationDataSource existingEamDataSource = dbManager.getDataSource(correlationCase, dataSource.getId());
                    dbManager.updateDataSourceName(existingEamDataSource, newName);
                } catch (CentralRepoException ex) {
                    LOGGER.log(Level.SEVERE, "Error updating data source with ID " + dataSource.getId() + " to " + newName, ex); //NON-NLS
                } catch (NoCurrentCaseException ex) {
                    LOGGER.log(Level.SEVERE, "No open case", ex);
                }
            }
        } // DATA_SOURCE_NAME_CHANGED
    }
}
