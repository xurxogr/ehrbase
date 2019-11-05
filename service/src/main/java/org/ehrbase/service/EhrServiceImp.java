/*
 * Copyright (c) 2019 Stefan Spiska (Vitasystems GmbH),
 * Jake Smolka (Hannover Medical School).
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehrbase.service;

import com.nedap.archie.rm.ehr.EhrStatus;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.HierObjectId;
import com.nedap.archie.rm.support.identification.PartyRef;
import org.ehrbase.api.definitions.CompositionFormat;
import org.ehrbase.api.definitions.StructuredString;
import org.ehrbase.api.definitions.StructuredStringFormat;
import org.ehrbase.api.dto.EhrStatusDto;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.exception.StateConflictException;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.dao.access.interfaces.I_ConceptAccess;
import org.ehrbase.dao.access.interfaces.I_EhrAccess;
import org.ehrbase.dao.access.interfaces.I_PartyIdentifiedAccess;
import org.ehrbase.serialisation.CanonicalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRED)
public class EhrServiceImp extends BaseService implements EhrService {
    public static final String MODIFIABLE = "modifiable";
    public static final String QUERYABLE = "queryable";
    public static final String SUBJECT_ID = "subjectId";
    public static final String SUBJECT_NAMESPACE = "subjectNamespace";
    public static final String DESCRIPTION = "description";
    private Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public EhrServiceImp(KnowledgeCacheService knowledgeCacheService, ConnectionPoolService connectionPoolService) {

        super(knowledgeCacheService, connectionPoolService);
    }

    @Override
    public UUID create(EhrStatus status, UUID ehrId) {

        if (status == null) {   // in case of new status with default values
            status = new EhrStatus();
            PartySelf partySelf = new PartySelf(new PartyRef(new HierObjectId(UUID.randomUUID().toString()), "default", null));
            status.setSubject(partySelf);
            status.setModifiable(true);
            status.setQueryable(true);
        }

        String subjectId = status.getSubject().getExternalRef().getId().getValue();
        String subjectNamespace = status.getSubject().getExternalRef().getNamespace();

        UUID subjectUuid = I_PartyIdentifiedAccess.getOrCreatePartyByExternalRef(getDataAccess(), null, subjectId, BaseService.DEMOGRAPHIC, subjectNamespace, BaseService.PARTY);
        UUID systemId = getSystemUuid();
        UUID committerId = getUserUuid();

        if (I_EhrAccess.checkExist(getDataAccess(), subjectUuid))
            throw new StateConflictException("Specified party has already an EHR set (partyId=" + subjectUuid + ")");

        try {   // this try block sums up a bunch of operations that can throw errors in the following
            I_EhrAccess ehrAccess = I_EhrAccess.getInstance(getDataAccess(), subjectUuid, systemId, null, null, ehrId);
            ehrAccess.setStatus(status);
            return ehrAccess.commit(committerId, systemId, DESCRIPTION);
        } catch (Exception e) {
            throw new InternalServerException("Could not create an EHR with given parameters.", e);
        }
    }

    @Override
    public Optional<EhrStatusDto> getEhrStatusEhrScape(UUID ehrUuid, CompositionFormat format) {
        EhrStatusDto statusDto = new EhrStatusDto();
        try {

            I_EhrAccess ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrUuid);
            if (ehrAccess == null) {
                return Optional.empty();
            }

            I_PartyIdentifiedAccess party = I_PartyIdentifiedAccess.retrieveInstance(getDataAccess(), ehrAccess.getParty());

            statusDto.setSubjectId(party.getPartyRefValue());
            statusDto.setSubjectNamespace(party.getPartyRefNamespace());
            statusDto.setModifiable(ehrAccess.isModifiable());
            statusDto.setQueryable(ehrAccess.isQueryable());
            statusDto.setOtherDetails(new StructuredString(new CanonicalJson().marshal(ehrAccess.getOtherDetails()), StructuredStringFormat.JSON));

        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }
        return Optional.of(statusDto);
    }

    @Override
    public Optional<EhrStatus> getEhrStatus(UUID ehrUuid) {
        //pre-step: check for valid ehrId
        if (hasEhr(ehrUuid).equals(Boolean.FALSE)) {
            throw new ObjectNotFoundException("ehr", "No EHR found with given ID: " + ehrUuid.toString());
        }

        try {

            I_EhrAccess ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrUuid);
            if (ehrAccess == null) {
                return Optional.empty();
            }

            return Optional.of(ehrAccess.getStatus());

        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }
    }


    @Override
    public Optional<EhrStatus> updateStatus(UUID ehrId, EhrStatus status) {
        //pre-step: check for valid ehrId
        if (hasEhr(ehrId).equals(Boolean.FALSE)) {
            throw new ObjectNotFoundException("ehr", "No EHR found with given ID: " + ehrId.toString());
        }

        I_EhrAccess ehrAccess;
        try {
            ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrId);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }
        if (ehrAccess == null) {
            return Optional.empty();
        }
        if (status != null) {
            ehrAccess.setStatus(status);
        }

        try {
            ehrAccess.update(getUserUuid(), getSystemUuid(), null, I_ConceptAccess.ContributionChangeType.MODIFICATION, DESCRIPTION);

        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }

        return getEhrStatus(ehrId);
    }

    @Override
    public Optional<UUID> findBySubject(String subjectId, String nameSpace) {
        UUID subjectUuid = I_PartyIdentifiedAccess.findReferencedParty(getDataAccess(), subjectId, BaseService.DEMOGRAPHIC, nameSpace, BaseService.PARTY);
        return Optional.ofNullable(I_EhrAccess.retrieveInstanceBySubject(getDataAccess(), subjectUuid));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean doesEhrExist(UUID ehrId) {
        Optional<I_EhrAccess> ehrAccess = Optional.ofNullable(I_EhrAccess.retrieveInstance(getDataAccess(), ehrId));
        return ehrAccess.isPresent();
    }

    /**
     * Fetches time of creation of specific EHR record
     *
     * @param ehrId
     * @return LocalDateTime instance of timestamp from DB
     */
    public LocalDateTime getCreationTime(UUID ehrId) {
        //pre-step: check for valid ehrId
        if (hasEhr(ehrId).equals(Boolean.FALSE)) {
            throw new ObjectNotFoundException("ehr", "No EHR found with given ID: " + ehrId.toString());
        }

        try {
            I_EhrAccess ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrId);
            return ehrAccess.getEhrRecord().getDateCreated().toLocalDateTime();
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }
    }

    // TODO build this for wrong requirement - so delete if really not needed at end of /ehr endpoints' implementation
    public String getLatestVersionedId(UUID ehrId) {
        try {
            I_EhrAccess ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrId);
            UUID statusId = ehrAccess.getStatusId();
            Integer version = ehrAccess.getLastVersionNumberOfStatus(getDataAccess(), statusId);

            // TODO this is the system's UUID but has no more details in DB though, so seems to be it in current state of available information - or is ehr.system.settings the correct URL style entry in DB?!
            String system = ehrAccess.getSystemId().toString();

            return ehrId.toString() + "::" + system + "::" + version;
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new InternalServerException(e);
        }
    }

    public Boolean hasEhr(UUID ehrId) {
        I_EhrAccess ehrAccess;
        try {
            ehrAccess = I_EhrAccess.retrieveInstance(getDataAccess(), ehrId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return ehrAccess != null;   // true if != null; false if == null
    }
}
