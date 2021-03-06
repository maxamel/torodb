/*
 *     This file is part of ToroDB.
 *
 *     ToroDB is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ToroDB is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with ToroDB. If not, see <http://www.gnu.org/licenses/>.
 *
 *     Copyright (c) 2014, 8Kdata Technology
 *     
 */

package com.toro.torod.connection;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.toro.torod.connection.update.Updator;
import com.torodb.torod.core.Session;
import com.torodb.torod.core.WriteFailMode;
import com.torodb.torod.core.config.DocumentBuilderFactory;
import com.torodb.torod.core.connection.*;
import com.torodb.torod.core.cursors.CursorId;
import com.torodb.torod.core.d2r.D2RTranslator;
import com.torodb.torod.core.exceptions.ToroImplementationException;
import com.torodb.torod.core.exceptions.UserToroException;
import com.torodb.torod.core.executor.SessionExecutor;
import com.torodb.torod.core.executor.SessionTransaction;
import com.torodb.torod.core.executor.ToroTaskExecutionException;
import com.torodb.torod.core.language.operations.DeleteOperation;
import com.torodb.torod.core.language.operations.UpdateOperation;
import com.torodb.torod.core.language.querycriteria.utils.EqualFactory;
import com.torodb.torod.core.language.update.UpdateAction;
import com.torodb.torod.core.subdocument.SplitDocument;
import com.torodb.torod.core.subdocument.ToroDocument;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 *
 */
public class DefaultToroTransaction implements ToroTransaction {

    private final SessionTransaction sessionTransaction;
    private final D2RTranslator d2r;
    private final SessionExecutor executor;
    private final DocumentBuilderFactory documentBuilderFactory;
    private final CursorManager cursorManager;
    
    DefaultToroTransaction(
            Session session,
            SessionTransaction sessionTransaction,
            D2RTranslator d2r,
            SessionExecutor executor,
            DocumentBuilderFactory documentBuilderFactory,
            CursorManager cursorManager
    ) {
        this.sessionTransaction = sessionTransaction;
        this.d2r = d2r;
        this.executor = executor;
        this.documentBuilderFactory = documentBuilderFactory;
        this.cursorManager = cursorManager;
    }

    @Override
    public void close() {
        sessionTransaction.close();
    }

    @Override
    public Future<?> rollback() {
        return sessionTransaction.rollback();
    }

    @Override
    public Future<?> commit() {
        return sessionTransaction.commit();
    }

    @Override
    public Future<InsertResponse> insertDocuments(
            String collection,
            Iterable<ToroDocument> documents,
            WriteFailMode mode
    ) {

        try {
            List<SplitDocument> documentsList = Lists.newArrayList();

            for (ToroDocument document : documents) {
                SplitDocument splitDoc
                        = d2r.translate(executor, collection, document);
                documentsList.add(splitDoc);
            }

            return sessionTransaction.insertSplitDocuments(
                    collection,
                    documentsList,
                    mode
            );

        }
        catch (ToroTaskExecutionException ex) {
            //TODO: Change exceptions
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Future<?> dropCollection(String collection) {
        return sessionTransaction.dropCollection(collection);
    }

    @Override
    public Future<DeleteResponse> delete(
            @Nonnull String collection,
            @Nonnull List<? extends DeleteOperation> deletes,
            @Nonnull WriteFailMode mode) {
        return sessionTransaction.delete(collection, deletes, mode);
    }

    @Override
    public Future<UpdateResponse> update(
            String collection,
            List<? extends UpdateOperation> updates,
            WriteFailMode mode
    ) {
        for (UpdateOperation update : updates) {
            if (update.isInsertIfNotFound()) {
                throw new UserToroException("Upsert updates are not supported");
            }
        }

        UpdateResponse.Builder builder = new UpdateResponse.Builder();
        for (int i = 0; i < updates.size(); i++) {
            UpdateOperation update = updates.get(i);

            try {
                update(collection, update, builder);
            }
            catch (InterruptedException ex) {
                throw new ToroImplementationException(ex);
            }
            catch (ExecutionException ex) {
                throw new ToroImplementationException(ex);
            }
            catch (UserToroException ex) {
                builder.addError(
                        new WriteError(
                                i,
                                -1,
                                ex.getLocalizedMessage()
                        )
                );
            }
        }

        return Futures.immediateCheckedFuture(builder.build());
    }

    private void update(
            String collection,
            UpdateOperation update,
            UpdateResponse.Builder responseBuilder
    ) throws InterruptedException, ExecutionException {

        CursorId cursor = cursorManager.openUnlimitedCursor(
                collection, 
                update.getQuery(), 
                null, 
                0, 
                true,
                false
        );
        List<ToroDocument> candidates = cursorManager.readAllCursor(cursor);

        responseBuilder.addCandidates(candidates.size());

        if (candidates.isEmpty()) {
            if (update.isInsertIfNotFound()) {
                ToroDocument documentToInsert = documentToInsert(update.
                        getAction());
                Future<InsertResponse> insertFuture
                        = insertDocuments(collection, Collections.singleton(
                                                  documentToInsert),
                                          WriteFailMode.TRANSACTIONAL);
                //as we are using a synchronized update, we need to wait until the insert is executed
                insertFuture.get();
            }
        }
        Set<ToroDocument> objectsToDelete = Sets.newHashSet();
        Set<ToroDocument> objectsToInsert = Sets.newHashSet();
        for (ToroDocument candidate : candidates) {
            ToroDocument newDoc = Updator.update(
                    candidate,
                    update.getAction(),
                    responseBuilder,
                    documentBuilderFactory
            );
            if (newDoc != null) {
                objectsToDelete.add(candidate);
                objectsToInsert.add(newDoc);

                if (update.isJustOne()) {
                    break;
                }
            }
        }
        if (!objectsToDelete.isEmpty()) {
            List<DeleteOperation> deletes = Lists.newArrayListWithCapacity(
                    objectsToDelete.size()
            );
            for (ToroDocument objectToDelete : objectsToDelete) {
                deletes.add(
                        new DeleteOperation(
                                EqualFactory.createEquality(objectToDelete),
                                true
                        )
                );
            }
            DeleteResponse deleteResponse
                    = delete(
                            collection,
                            deletes,
                            WriteFailMode.TRANSACTIONAL
                    ).get();
            if (deleteResponse.getDeleted() != objectsToDelete.size()) {
                throw new ToroImplementationException("Update: "
                        + objectsToDelete.size() + " should be deleted, but "
                        + deleteResponse.getDeleted() + " objects have been "
                        + "deleted instead");
            }
        }
        if (!objectsToInsert.isEmpty()) {
            InsertResponse insertResponse
                    = insertDocuments(
                            collection,
                            objectsToInsert,
                            WriteFailMode.TRANSACTIONAL
                    ).get();
            if (insertResponse.getInsertedSize() != objectsToInsert.size()) {
                throw new ToroImplementationException("Update: "
                        + objectsToInsert.size() + " should be inserted, but "
                        + insertResponse.getInsertedSize() + " objects have "
                        + "been inserted instead");
            }
        }

    }

    private ToroDocument documentToInsert(UpdateAction action) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

}
