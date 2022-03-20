/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 *
 */

package io.supertokens.storage.sql;

import ch.qos.logback.classic.Logger;
import com.google.gson.JsonObject;
import io.supertokens.pluginInterface.KeyValueInfo;
import io.supertokens.pluginInterface.RECIPE_ID;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeUserInfo;
import io.supertokens.pluginInterface.emailpassword.PasswordResetTokenInfo;
import io.supertokens.pluginInterface.emailpassword.UserInfo;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicateEmailException;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicatePasswordResetTokenException;
import io.supertokens.pluginInterface.emailpassword.exceptions.DuplicateUserIdException;
import io.supertokens.pluginInterface.emailpassword.exceptions.UnknownUserIdException;
import io.supertokens.pluginInterface.emailpassword.sqlStorage.EmailPasswordSQLStorage;
import io.supertokens.pluginInterface.emailverification.EmailVerificationTokenInfo;
import io.supertokens.pluginInterface.emailverification.exception.DuplicateEmailVerificationTokenException;
import io.supertokens.pluginInterface.emailverification.sqlStorage.EmailVerificationSQLStorage;
import io.supertokens.pluginInterface.exceptions.QuitProgramFromPluginException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.jwt.JWTSigningKeyInfo;
import io.supertokens.pluginInterface.jwt.exceptions.DuplicateKeyIdException;
import io.supertokens.pluginInterface.jwt.sqlstorage.JWTRecipeSQLStorage;
import io.supertokens.pluginInterface.passwordless.PasswordlessCode;
import io.supertokens.pluginInterface.passwordless.PasswordlessDevice;
import io.supertokens.pluginInterface.passwordless.exception.*;
import io.supertokens.pluginInterface.passwordless.sqlStorage.PasswordlessSQLStorage;
import io.supertokens.pluginInterface.session.SessionInfo;
import io.supertokens.pluginInterface.session.sqlStorage.SessionSQLStorage;
import io.supertokens.pluginInterface.sqlStorage.TransactionConnection;
import io.supertokens.pluginInterface.thirdparty.exception.DuplicateThirdPartyUserException;
import io.supertokens.pluginInterface.thirdparty.sqlStorage.ThirdPartySQLStorage;
import io.supertokens.pluginInterface.usermetadata.sqlStorage.UserMetadataSQLStorage;
import io.supertokens.storage.sql.config.Config;
import io.supertokens.storage.sql.config.PostgreSQLConfig;
import io.supertokens.storage.sql.output.Logging;
import io.supertokens.storage.sql.queries.*;
import org.hibernate.Session;
import org.hibernate.exception.LockAcquisitionException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.persistence.OptimisticLockException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.List;

public class Start implements SessionSQLStorage, EmailPasswordSQLStorage, EmailVerificationSQLStorage,
        ThirdPartySQLStorage, JWTRecipeSQLStorage, PasswordlessSQLStorage, UserMetadataSQLStorage {

    private static final Object appenderLock = new Object();
    public static boolean silent = false;
    private ResourceDistributor resourceDistributor = new ResourceDistributor();
    private String processId;
    private HikariLoggingAppender hikariLoggingAppender = new HikariLoggingAppender(this);
    private HibernateLoggingAppender hibernateAppender = new HibernateLoggingAppender(this);
    private JBossLoggingAppender jbossAppender = new JBossLoggingAppender(this);
    private static final String APP_ID_KEY_NAME = "app_id";
    private static final String ACCESS_TOKEN_SIGNING_KEY_NAME = "access_token_signing_key";
    private static final String REFRESH_TOKEN_KEY_NAME = "refresh_token_key";
    public static boolean isTesting = false;
    boolean enabled = true;
    Thread mainThread = Thread.currentThread();
    private Thread shutdownHook;

    public ResourceDistributor getResourceDistributor() {
        return resourceDistributor;
    }

    public String getProcessId() {
        return this.processId;
    }

    @Override
    public void constructor(String processId, boolean silent) {
        mainThread = Thread.currentThread();
        this.processId = processId;
        Start.silent = silent;
    }

    @Override
    public STORAGE_TYPE getType() {
        return STORAGE_TYPE.SQL;
    }

    @Override
    public void loadConfig(String configFilePath) {
        Config.loadConfig(this, configFilePath);
    }

    @Override
    public void initFileLogging(String infoLogPath, String errorLogPath) {
        Logging.initFileLogging(this, infoLogPath, errorLogPath);

        /*
         * NOTE: The log this produces is only accurate in production or development.
         *
         * For testing, it may happen that multiple processes are running at the same
         * time which can lead to one of them being the winner and its start instance
         * being attached to logger class. This would yield inaccurate processIds during
         * logging.
         *
         * Finally, during testing, the winner's logger might be removed, in which case
         * nothing will be handling logging and hikari's logs would not be outputed
         * anywhere.
         */
        synchronized (appenderLock) {
            final Logger hikariInfoLog = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari");
            if (hikariInfoLog.getAppender(HikariLoggingAppender.NAME) == null) {
                hikariInfoLog.setAdditive(false);
                hikariInfoLog.addAppender(hikariLoggingAppender);
            }

            final Logger hibernateInfoLog = (Logger) LoggerFactory.getLogger("org.hibernate");
            if (hibernateInfoLog.getAppender(HibernateLoggingAppender.NAME) == null) {
                hibernateInfoLog.setAdditive(false);
                hibernateInfoLog.addAppender(hibernateAppender);
            }

            final Logger jbossInfoLog = (Logger) LoggerFactory.getLogger("org.jboss");
            if (jbossInfoLog.getAppender(JBossLoggingAppender.NAME) == null) {
                jbossInfoLog.setAdditive(false);
                jbossInfoLog.addAppender(jbossAppender);
            }
        }

    }

    @Override
    public void stopLogging() {
        Logging.stopLogging(this);

        synchronized (appenderLock) {
            final Logger hikariInfoLog = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari");
            if (hikariInfoLog.getAppender(HikariLoggingAppender.NAME) != null) {
                hikariInfoLog.detachAppender(HikariLoggingAppender.NAME);
            }

            final Logger hibernateInfoLog = (Logger) LoggerFactory.getLogger("org.hibernate");
            if (hibernateInfoLog.getAppender(HibernateLoggingAppender.NAME) != null) {
                hibernateInfoLog.detachAppender(HibernateLoggingAppender.NAME);
            }

            final Logger jbossInfoLog = (Logger) LoggerFactory.getLogger("org.jboss");
            if (jbossInfoLog.getAppender(JBossLoggingAppender.NAME) != null) {
                jbossInfoLog.detachAppender(JBossLoggingAppender.NAME);
            }
        }
    }

    @Override
    public void initStorage() {
        ConnectionPool.initPool(this);
        try {
            GeneralQueries.createTablesIfNotExists(this);
        } catch (SQLException | StorageQueryException e) {
            throw new QuitProgramFromPluginException(e);
        }
    }

    @Override
    public <T> T startTransaction(TransactionLogic<T> logic)
            throws StorageTransactionLogicException, StorageQueryException {
        return startTransaction(logic, TransactionIsolationLevel.SERIALIZABLE);
    }

    @Override
    public <T> T startTransaction(TransactionLogic<T> logic, TransactionIsolationLevel isolationLevel)
            throws StorageTransactionLogicException, StorageQueryException {
        int tries = 0;
        while (true) {
            tries++;
            try {
                return startTransactionHelper(logic, isolationLevel);
            } catch (OptimisticLockException | LockAcquisitionException | SQLException | StorageQueryException
                    | StorageTransactionLogicException e) {
                Throwable actualException = e;
                if (e instanceof StorageQueryException) {
                    actualException = e.getCause();
                } else if (e instanceof StorageTransactionLogicException) {
                    actualException = ((StorageTransactionLogicException) e).actualException;
                } else if (e instanceof LockAcquisitionException) {
                    // LockAcquisitionException -> PSQLException
                    actualException = e.getCause();
                } else if (e instanceof OptimisticLockException) {
                    // OptimisticLockException -> LockAcquisitionException -> PSQLException
                    actualException = e.getCause().getCause();
                }
                String exceptionMessage = actualException.getMessage();
                if (exceptionMessage == null) {
                    exceptionMessage = "";
                }
                // see: https://github.com/supertokens/supertokens-postgresql-plugin/pull/3

                // We set this variable to the current (or cause) exception casted to
                // PSQLException if we can safely cast it
                PSQLException psqlException = actualException instanceof PSQLException ? (PSQLException) actualException
                        : null;

                // PSQL error class 40 is transaction rollback. See:
                // https://www.postgresql.org/docs/12/errcodes-appendix.html
                boolean isPSQLRollbackException = psqlException != null
                        && psqlException.getServerErrorMessage().getSQLState().startsWith("40");

                // We keep the old exception detection logic to ensure backwards compatibility.
                // We could get here if the new logic hits a false negative,
                // e.g., in case someone renamed constraints/tables
                boolean isDeadlockException = actualException instanceof SQLTransactionRollbackException
                        || exceptionMessage.toLowerCase().contains("concurrent update")
                        || exceptionMessage.toLowerCase().contains("the transaction might succeed if retried") ||

                        // we have deadlock as well due to the DeadlockTest.java
                        exceptionMessage.toLowerCase().contains("deadlock");

                if ((isPSQLRollbackException || isDeadlockException) && tries < 3) {
                    try {
                        Thread.sleep((long) (10 + (Math.random() * 20)));
                    } catch (InterruptedException ignored) {
                    }
                    ProcessState.getInstance(this).addState(ProcessState.PROCESS_STATE.DEADLOCK_FOUND, e);
                    // this because deadlocks are not necessarily a result of faulty logic. They can happen
                    continue;
                }
                if (e instanceof StorageQueryException) {
                    throw (StorageQueryException) e;
                } else if (e instanceof StorageTransactionLogicException) {
                    throw (StorageTransactionLogicException) e;
                }
                throw new StorageQueryException(e);
            }
        }
    }

    private <T> T startTransactionHelper(TransactionLogic<T> logic, TransactionIsolationLevel isolationLevel)
            throws StorageQueryException, StorageTransactionLogicException, SQLException {
        return ConnectionPool.withSessionForComplexTransaction(this, isolationLevel,
                (session, con) -> logic.mainLogicAndCommit(new TransactionConnection(con, session)));
    }

    @Override
    public void commitTransaction(TransactionConnection con) throws StorageQueryException {
        Object session = con.getSession();
        if (session != null && ((Session) session).isJoinedToTransaction()) {
            ((Session) session).getTransaction().commit();
        } else {
            Connection sqlCon = (Connection) con.getConnection();
            try {
                sqlCon.commit();
            } catch (SQLException e) {
                throw new StorageQueryException(e);
            }
        }

    }

    @Override
    public KeyValueInfo getLegacyAccessTokenSigningKey_Transaction(TransactionConnection con) {
        Session session = (Session) con.getSession();
        return GeneralQueries.getKeyValue_Transaction(session, ACCESS_TOKEN_SIGNING_KEY_NAME);
    }

    @Override
    public void removeLegacyAccessTokenSigningKey_Transaction(TransactionConnection con) {
        Session session = (Session) con.getSession();
        GeneralQueries.deleteKeyValue_Transaction(session, ACCESS_TOKEN_SIGNING_KEY_NAME);
    }

    @Override
    public KeyValueInfo[] getAccessTokenSigningKeys_Transaction(TransactionConnection con)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return SessionQueries.getAccessTokenSigningKeys_Transaction(this, sqlCon);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void addAccessTokenSigningKey_Transaction(TransactionConnection con, KeyValueInfo info)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            SessionQueries.addAccessTokenSigningKey_Transaction(this, sqlCon, info.createdAtTime, info.value);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void removeAccessTokenSigningKeysBefore(long time) throws StorageQueryException {
        try {
            SessionQueries.removeAccessTokenSigningKeysBefore(this, time);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public KeyValueInfo getRefreshTokenSigningKey_Transaction(TransactionConnection con) {
        Session session = (Session) con.getSession();
        return GeneralQueries.getKeyValue_Transaction(session, REFRESH_TOKEN_KEY_NAME);
    }

    @Override
    public void setRefreshTokenSigningKey_Transaction(TransactionConnection con, KeyValueInfo info) {
        Session session = (Session) con.getSession();
        GeneralQueries.setKeyValue_Transaction(session, REFRESH_TOKEN_KEY_NAME, info);
    }

    @TestOnly
    @Override
    public void deleteAllInformation() throws StorageQueryException {
        try {
            GeneralQueries.deleteAllTables(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void close() {
        ConnectionPool.close(this);
    }

    @Override
    public void createNewSession(String sessionHandle, String userId, String refreshTokenHash2,
            JsonObject userDataInDatabase, long expiry, JsonObject userDataInJWT, long createdAtTime)
            throws StorageQueryException {
        try {
            SessionQueries.createNewSession(this, sessionHandle, userId, refreshTokenHash2, userDataInDatabase, expiry,
                    userDataInJWT, createdAtTime);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteSessionsOfUser(String userId) throws StorageQueryException {
        try {
            SessionQueries.deleteSessionsOfUser(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public int getNumberOfSessions() throws StorageQueryException {
        try {
            return SessionQueries.getNumberOfSessions(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public int deleteSession(String[] sessionHandles) throws StorageQueryException {
        try {
            return SessionQueries.deleteSession(this, sessionHandles);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public String[] getAllSessionHandlesForUser(String userId) throws StorageQueryException {
        try {
            return SessionQueries.getAllSessionHandlesForUser(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteAllExpiredSessions() throws StorageQueryException {
        try {
            SessionQueries.deleteAllExpiredSessions(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public KeyValueInfo getKeyValue(String key) throws StorageQueryException {
        try {
            return GeneralQueries.getKeyValue(this, key);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void setKeyValue(String key, KeyValueInfo info) throws StorageQueryException {
        try {
            GeneralQueries.setKeyValue(this, key, info);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void setStorageLayerEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public SessionInfo getSession(String sessionHandle) throws StorageQueryException {
        try {
            return SessionQueries.getSession(this, sessionHandle);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public int updateSession(String sessionHandle, JsonObject sessionData, JsonObject jwtPayload)
            throws StorageQueryException {
        try {
            return SessionQueries.updateSession(this, sessionHandle, sessionData, jwtPayload);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public SessionInfo getSessionInfo_Transaction(TransactionConnection con, String sessionHandle)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return SessionQueries.getSessionInfo_Transaction(this, sqlCon, sessionHandle);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateSessionInfo_Transaction(TransactionConnection con, String sessionHandle, String refreshTokenHash2,
            long expiry) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            SessionQueries.updateSessionInfo_Transaction(this, sqlCon, sessionHandle, refreshTokenHash2, expiry);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void setKeyValue_Transaction(TransactionConnection con, String key, KeyValueInfo info) {
        Session session = (Session) con.getSession();
        GeneralQueries.setKeyValue_Transaction(session, key, info);
    }

    @Override
    public KeyValueInfo getKeyValue_Transaction(TransactionConnection con, String key) {
        Session session = (Session) con.getSession();
        return GeneralQueries.getKeyValue_Transaction(session, key);
    }

    void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                shutdownHook = null;
            } catch (IllegalStateException ignored) {
            }
        }
    }

    void handleKillSignalForWhenItHappens() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(() -> {
            mainThread.interrupt();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public boolean canBeUsed(String configFilePath) {
        return Config.canBeUsed(configFilePath);
    }

    @Override
    public void signUp(UserInfo userInfo)
            throws StorageQueryException, DuplicateUserIdException, DuplicateEmailException {
        try {
            EmailPasswordQueries.signUp(this, userInfo.id, userInfo.email, userInfo.passwordHash, userInfo.timeJoined);
        } catch (StorageTransactionLogicException eTemp) {
            Exception e = eTemp.actualException;
            if (e instanceof PSQLException) {
                PostgreSQLConfig config = Config.getConfig(this);
                ServerErrorMessage serverMessage = ((PSQLException) e).getServerErrorMessage();

                if (isUniqueConstraintError(serverMessage, config.getEmailPasswordUsersTable(), "email")) {
                    throw new DuplicateEmailException();
                } else if (isPrimaryKeyError(serverMessage, config.getEmailPasswordUsersTable())
                        || isPrimaryKeyError(serverMessage, config.getUsersTable())) {
                    throw new DuplicateUserIdException();
                }
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (email)")) {
                throw new DuplicateEmailException();
            } else if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (user_id)")) {
                throw new DuplicateUserIdException();
            }

            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteEmailPasswordUser(String userId) throws StorageQueryException {
        try {
            EmailPasswordQueries.deleteUser(this, userId);
        } catch (StorageTransactionLogicException e) {
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public UserInfo getUserInfoUsingId(String id) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getUserInfoUsingId(this, id);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public UserInfo getUserInfoUsingEmail(String email) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getUserInfoUsingEmail(this, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void addPasswordResetToken(PasswordResetTokenInfo passwordResetTokenInfo)
            throws StorageQueryException, UnknownUserIdException, DuplicatePasswordResetTokenException {
        try {
            EmailPasswordQueries.addPasswordResetToken(this, passwordResetTokenInfo.userId,
                    passwordResetTokenInfo.token, passwordResetTokenInfo.tokenExpiry);
        } catch (SQLException e) {
            if (e instanceof PSQLException) {
                ServerErrorMessage serverMessage = ((PSQLException) e).getServerErrorMessage();

                if (isPrimaryKeyError(serverMessage, Config.getConfig(this).getPasswordResetTokensTable())) {
                    throw new DuplicatePasswordResetTokenException();
                } else if (isForeignKeyConstraintError(serverMessage,
                        Config.getConfig(this).getPasswordResetTokensTable(), "user_id")) {
                    throw new UnknownUserIdException();
                }
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (user_id, token)")) {
                throw new DuplicatePasswordResetTokenException();
            } else if (e.getMessage().contains("foreign key") && e.getMessage().contains("user_id")) {
                throw new UnknownUserIdException();
            }
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordResetTokenInfo getPasswordResetTokenInfo(String token) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getPasswordResetTokenInfo(this, token);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordResetTokenInfo[] getAllPasswordResetTokenInfoForUser(String userId) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getAllPasswordResetTokenInfoForUser(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordResetTokenInfo[] getAllPasswordResetTokenInfoForUser_Transaction(TransactionConnection con,
            String userId) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return EmailPasswordQueries.getAllPasswordResetTokenInfoForUser_Transaction(this, sqlCon, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteAllPasswordResetTokensForUser_Transaction(TransactionConnection con, String userId)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            EmailPasswordQueries.deleteAllPasswordResetTokensForUser_Transaction(this, sqlCon, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateUsersPassword_Transaction(TransactionConnection con, String userId, String newPassword)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            EmailPasswordQueries.updateUsersPassword_Transaction(this, sqlCon, userId, newPassword);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateUsersEmail_Transaction(TransactionConnection conn, String userId, String email)
            throws StorageQueryException, DuplicateEmailException {
        Connection sqlCon = (Connection) conn.getConnection();
        try {
            EmailPasswordQueries.updateUsersEmail_Transaction(this, sqlCon, userId, email);
        } catch (SQLException e) {
            if (e instanceof PSQLException && isUniqueConstraintError(((PSQLException) e).getServerErrorMessage(),
                    Config.getConfig(this).getEmailPasswordUsersTable(), "email")) {
                throw new DuplicateEmailException();
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (email)")) {
                throw new DuplicateEmailException();
            }
            throw new StorageQueryException(e);
        }
    }

    @Override
    public UserInfo getUserInfoUsingId_Transaction(TransactionConnection con, String userId)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return EmailPasswordQueries.getUserInfoUsingId_Transaction(this, sqlCon, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteExpiredEmailVerificationTokens() throws StorageQueryException {
        try {
            EmailVerificationQueries.deleteExpiredEmailVerificationTokens(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public EmailVerificationTokenInfo[] getAllEmailVerificationTokenInfoForUser_Transaction(TransactionConnection con,
            String userId, String email) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return EmailVerificationQueries.getAllEmailVerificationTokenInfoForUser_Transaction(this, sqlCon, userId,
                    email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteAllEmailVerificationTokensForUser_Transaction(TransactionConnection con, String userId,
            String email) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            EmailVerificationQueries.deleteAllEmailVerificationTokensForUser_Transaction(this, sqlCon, userId, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateIsEmailVerified_Transaction(TransactionConnection con, String userId, String email,
            boolean isEmailVerified) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            EmailVerificationQueries.updateUsersIsEmailVerified_Transaction(this, sqlCon, userId, email,
                    isEmailVerified);
        } catch (SQLException e) {
            boolean isPSQLPrimKeyError = e instanceof PSQLException && isPrimaryKeyError(
                    ((PSQLException) e).getServerErrorMessage(), Config.getConfig(this).getEmailVerificationTable());

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            boolean isDuplicateKeyError = e.getMessage().contains("ERROR: duplicate key")
                    && e.getMessage().contains("Key (user_id, email)");

            if (!isEmailVerified || (!isPSQLPrimKeyError && !isDuplicateKeyError)) {
                throw new StorageQueryException(e);
            }
            // we do not throw an error since the email is already verified
        }
    }

    @Override
    public void deleteEmailVerificationUserInfo(String userId) throws StorageQueryException {
        try {
            EmailVerificationQueries.deleteUserInfo(this, userId);
        } catch (StorageTransactionLogicException e) {
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public void addEmailVerificationToken(EmailVerificationTokenInfo emailVerificationInfo)
            throws StorageQueryException, DuplicateEmailVerificationTokenException {
        try {
            EmailVerificationQueries.addEmailVerificationToken(this, emailVerificationInfo.userId,
                    emailVerificationInfo.token, emailVerificationInfo.tokenExpiry, emailVerificationInfo.email);
        } catch (SQLException e) {
            if (e instanceof PSQLException && isPrimaryKeyError(((PSQLException) e).getServerErrorMessage(),
                    Config.getConfig(this).getEmailVerificationTokensTable())) {
                throw new DuplicateEmailVerificationTokenException();
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key")
                    && e.getMessage().contains("Key (user_id, email, token)")) {
                throw new DuplicateEmailVerificationTokenException();
            }
            throw new StorageQueryException(e);
        }
    }

    @Override
    public EmailVerificationTokenInfo getEmailVerificationTokenInfo(String token) throws StorageQueryException {
        try {
            return EmailVerificationQueries.getEmailVerificationTokenInfo(this, token);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void revokeAllTokens(String userId, String email) throws StorageQueryException {
        try {
            EmailVerificationQueries.revokeAllTokens(this, userId, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void unverifyEmail(String userId, String email) throws StorageQueryException {
        try {
            EmailVerificationQueries.unverifyEmail(this, userId, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public EmailVerificationTokenInfo[] getAllEmailVerificationTokenInfoForUser(String userId, String email)
            throws StorageQueryException {
        try {
            return EmailVerificationQueries.getAllEmailVerificationTokenInfoForUser(this, userId, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public boolean isEmailVerified(String userId, String email) throws StorageQueryException {
        try {
            return EmailVerificationQueries.isEmailVerified(this, userId, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public UserInfo[] getUsers(@Nonnull String userId, @Nonnull Long timeJoined, @Nonnull Integer limit,
            @Nonnull String timeJoinedOrder) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getUsersInfo(this, userId, timeJoined, limit, timeJoinedOrder);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public UserInfo[] getUsers(@Nonnull Integer limit, @Nonnull String timeJoinedOrder) throws StorageQueryException {
        try {
            return EmailPasswordQueries.getUsersInfo(this, limit, timeJoinedOrder);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public long getUsersCount() throws StorageQueryException {
        try {
            return EmailPasswordQueries.getUsersCount(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteExpiredPasswordResetTokens() throws StorageQueryException {
        try {
            EmailPasswordQueries.deleteExpiredPasswordResetTokens(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.thirdparty.UserInfo getUserInfoUsingId_Transaction(TransactionConnection con,
            String thirdPartyId, String thirdPartyUserId) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return ThirdPartyQueries.getUserInfoUsingId_Transaction(this, sqlCon, thirdPartyId, thirdPartyUserId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateUserEmail_Transaction(TransactionConnection con, String thirdPartyId, String thirdPartyUserId,
            String newEmail) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            ThirdPartyQueries.updateUserEmail_Transaction(this, sqlCon, thirdPartyId, thirdPartyUserId, newEmail);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void signUp(io.supertokens.pluginInterface.thirdparty.UserInfo userInfo)
            throws StorageQueryException, io.supertokens.pluginInterface.thirdparty.exception.DuplicateUserIdException,
            DuplicateThirdPartyUserException {
        try {
            ThirdPartyQueries.signUp(this, userInfo);
        } catch (StorageTransactionLogicException eTemp) {
            Exception e = eTemp.actualException;
            if (e instanceof PSQLException) {
                ServerErrorMessage serverMessage = ((PSQLException) e).getServerErrorMessage();
                if (isPrimaryKeyError(serverMessage, Config.getConfig(this).getThirdPartyUsersTable())) {
                    throw new DuplicateThirdPartyUserException();
                } else if (isPrimaryKeyError(serverMessage, Config.getConfig(this).getUsersTable())) {
                    throw new io.supertokens.pluginInterface.thirdparty.exception.DuplicateUserIdException();
                }
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key")
                    && e.getMessage().contains("Key (third_party_id, third_party_user_id)")) {
                throw new DuplicateThirdPartyUserException();
            } else if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (user_id)")) {
                throw new io.supertokens.pluginInterface.thirdparty.exception.DuplicateUserIdException();
            }

            throw new StorageQueryException(eTemp.actualException);
        }
    }

    @Override
    public void deleteThirdPartyUser(String userId) throws StorageQueryException {
        try {
            ThirdPartyQueries.deleteUser(this, userId);
        } catch (StorageTransactionLogicException e) {
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public io.supertokens.pluginInterface.thirdparty.UserInfo getThirdPartyUserInfoUsingId(String thirdPartyId,
            String thirdPartyUserId) throws StorageQueryException {
        try {
            return ThirdPartyQueries.getThirdPartyUserInfoUsingId(this, thirdPartyId, thirdPartyUserId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.thirdparty.UserInfo getThirdPartyUserInfoUsingId(String id)
            throws StorageQueryException {
        try {
            return ThirdPartyQueries.getThirdPartyUserInfoUsingId(this, id);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public io.supertokens.pluginInterface.thirdparty.UserInfo[] getThirdPartyUsers(@NotNull String userId,
            @NotNull Long timeJoined, @NotNull Integer limit, @NotNull String timeJoinedOrder)
            throws StorageQueryException {
        try {
            return ThirdPartyQueries.getThirdPartyUsers(this, userId, timeJoined, limit, timeJoinedOrder);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public io.supertokens.pluginInterface.thirdparty.UserInfo[] getThirdPartyUsers(@NotNull Integer limit,
            @NotNull String timeJoinedOrder) throws StorageQueryException {
        try {
            return ThirdPartyQueries.getThirdPartyUsers(this, limit, timeJoinedOrder);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    @Deprecated
    public long getThirdPartyUsersCount() throws StorageQueryException {
        try {
            return ThirdPartyQueries.getUsersCount(this);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.thirdparty.UserInfo[] getThirdPartyUsersByEmail(@NotNull String email)
            throws StorageQueryException {
        try {
            return ThirdPartyQueries.getThirdPartyUsersByEmail(this, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public long getUsersCount(RECIPE_ID[] includeRecipeIds) throws StorageQueryException {
        try {
            return GeneralQueries.getUsersCount(this, includeRecipeIds);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public AuthRecipeUserInfo[] getUsers(@NotNull Integer limit, @NotNull String timeJoinedOrder,
            @Nullable RECIPE_ID[] includeRecipeIds, @Nullable String userId, @Nullable Long timeJoined)
            throws StorageQueryException {
        try {
            return GeneralQueries.getUsers(this, limit, timeJoinedOrder, includeRecipeIds, userId, timeJoined);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public List<JWTSigningKeyInfo> getJWTSigningKeys_Transaction(TransactionConnection con)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return JWTSigningQueries.getJWTSigningKeys_Transaction(this, sqlCon);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void setJWTSigningKey_Transaction(TransactionConnection con, JWTSigningKeyInfo info)
            throws StorageQueryException, DuplicateKeyIdException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            JWTSigningQueries.setJWTSigningKeyInfo_Transaction(this, sqlCon, info);
        } catch (SQLException e) {
            if (e instanceof PSQLException && isPrimaryKeyError(((PSQLException) e).getServerErrorMessage(),
                    Config.getConfig(this).getJWTSigningKeysTable())) {
                throw new DuplicateKeyIdException();
            }

            // We keep the old exception detection logic to ensure backwards compatibility.
            // We could get here if the new logic hits a false negative,
            // e.g., in case someone renamed constraints/tables
            if (e.getMessage().contains("ERROR: duplicate key") && e.getMessage().contains("Key (key_id)")) {
                throw new DuplicateKeyIdException();
            }

            throw new StorageQueryException(e);
        }
    }

    private boolean isUniqueConstraintError(ServerErrorMessage serverMessage, String tableName, String columnName) {
        return serverMessage.getSQLState().equals("23505") && serverMessage.getConstraint() != null
                && serverMessage.getConstraint().equals(tableName + "_" + columnName + "_key");
    }

    private boolean isForeignKeyConstraintError(ServerErrorMessage serverMessage, String tableName, String columnName) {
        return serverMessage.getSQLState().equals("23503") && serverMessage.getConstraint() != null
                && serverMessage.getConstraint().equals(tableName + "_" + columnName + "_fkey");
    }

    private boolean isPrimaryKeyError(ServerErrorMessage serverMessage, String tableName) {
        return serverMessage.getSQLState().equals("23505") && serverMessage.getConstraint() != null
                && serverMessage.getConstraint().equals(tableName + "_pkey");
    }

    @Override
    public PasswordlessDevice getDevice_Transaction(TransactionConnection con, String deviceIdHash)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return PasswordlessQueries.getDevice_Transaction(this, sqlCon, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void incrementDeviceFailedAttemptCount_Transaction(TransactionConnection con, String deviceIdHash)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            PasswordlessQueries.incrementDeviceFailedAttemptCount_Transaction(this, sqlCon, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }

    }

    @Override
    public PasswordlessCode[] getCodesOfDevice_Transaction(TransactionConnection con, String deviceIdHash)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return PasswordlessQueries.getCodesOfDevice_Transaction(this, sqlCon, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteDevice_Transaction(TransactionConnection con, String deviceIdHash) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            PasswordlessQueries.deleteDevice_Transaction(this, sqlCon, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }

    }

    @Override
    public void deleteDevicesByPhoneNumber_Transaction(TransactionConnection con, @Nonnull String phoneNumber)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            PasswordlessQueries.deleteDevicesByPhoneNumber_Transaction(this, sqlCon, phoneNumber);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteDevicesByEmail_Transaction(TransactionConnection con, @Nonnull String email)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            PasswordlessQueries.deleteDevicesByEmail_Transaction(this, sqlCon, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessCode getCodeByLinkCodeHash_Transaction(TransactionConnection con, String linkCodeHash)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return PasswordlessQueries.getCodeByLinkCodeHash_Transaction(this, sqlCon, linkCodeHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void deleteCode_Transaction(TransactionConnection con, String deviceIdHash) throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            PasswordlessQueries.deleteCode_Transaction(this, sqlCon, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public void updateUserEmail_Transaction(TransactionConnection con, String userId, String email)
            throws StorageQueryException, UnknownUserIdException, DuplicateEmailException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            int updated_rows = PasswordlessQueries.updateUserEmail_Transaction(this, sqlCon, userId, email);
            if (updated_rows != 1) {
                throw new UnknownUserIdException();
            }
        } catch (SQLException e) {

            if (e instanceof PSQLException) {
                if (isUniqueConstraintError(((PSQLException) e).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessUsersTable(), "email")) {
                    throw new DuplicateEmailException();

                }
            }
            throw new StorageQueryException(e);

        }
    }

    @Override
    public void updateUserPhoneNumber_Transaction(TransactionConnection con, String userId, String phoneNumber)
            throws StorageQueryException, UnknownUserIdException, DuplicatePhoneNumberException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            int updated_rows = PasswordlessQueries.updateUserPhoneNumber_Transaction(this, sqlCon, userId, phoneNumber);

            if (updated_rows != 1) {
                throw new UnknownUserIdException();
            }

        } catch (SQLException e) {

            if (e instanceof PSQLException) {
                if (isUniqueConstraintError(((PSQLException) e).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessUsersTable(), "phone_number")) {
                    throw new DuplicatePhoneNumberException();

                }
            }

            throw new StorageQueryException(e);
        }
    }

    @Override
    public void createDeviceWithCode(@Nullable String email, @Nullable String phoneNumber, @NotNull String linkCodeSalt,
            PasswordlessCode code) throws StorageQueryException, DuplicateDeviceIdHashException,
            DuplicateCodeIdException, DuplicateLinkCodeHashException {
        if (email == null && phoneNumber == null) {
            throw new IllegalArgumentException("Both email and phoneNumber can't be null");
        }
        try {
            PasswordlessQueries.createDeviceWithCode(this, email, phoneNumber, linkCodeSalt, code);
        } catch (StorageTransactionLogicException e) {
            Exception actualException = e.actualException;

            if (actualException instanceof PSQLException) {
                if (isPrimaryKeyError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessDevicesTable())) {
                    throw new DuplicateDeviceIdHashException();
                }
                if (isPrimaryKeyError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessCodesTable())) {
                    throw new DuplicateCodeIdException();
                }
                if (isUniqueConstraintError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessCodesTable(), "link_code_hash")) {
                    throw new DuplicateLinkCodeHashException();

                }
            }

            throw new StorageQueryException(e);
        }
    }

    @Override
    public void createCode(PasswordlessCode code) throws StorageQueryException, UnknownDeviceIdHash,
            DuplicateCodeIdException, DuplicateLinkCodeHashException {

        try {
            PasswordlessQueries.createCode(this, code);
        } catch (StorageTransactionLogicException e) {

            Exception actualException = e.actualException;

            if (actualException instanceof PSQLException) {
                if (isForeignKeyConstraintError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessCodesTable(), "device_id_hash")) {
                    throw new UnknownDeviceIdHash();
                }
                if (isPrimaryKeyError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessCodesTable())) {
                    throw new DuplicateCodeIdException();
                }
                if (isUniqueConstraintError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessCodesTable(), "link_code_hash")) {
                    throw new DuplicateLinkCodeHashException();

                }
            }
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public void createUser(io.supertokens.pluginInterface.passwordless.UserInfo user) throws StorageQueryException,
            DuplicateEmailException, DuplicatePhoneNumberException, DuplicateUserIdException {
        try {
            PasswordlessQueries.createUser(this, user);
        } catch (StorageTransactionLogicException e) {

            String message = e.actualException.getMessage();
            Exception actualException = e.actualException;

            if (actualException instanceof PSQLException) {
                if (isPrimaryKeyError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessUsersTable())
                        || isPrimaryKeyError(((PSQLException) actualException).getServerErrorMessage(),
                                Config.getConfig(this).getUsersTable())) {
                    throw new DuplicateUserIdException();
                }

                if (isUniqueConstraintError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessUsersTable(), "email")) {
                    throw new DuplicateEmailException();
                }

                if (isUniqueConstraintError(((PSQLException) actualException).getServerErrorMessage(),
                        Config.getConfig(this).getPasswordlessUsersTable(), "phone_number")) {
                    throw new DuplicatePhoneNumberException();
                }

            }
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public void deletePasswordlessUser(String userId) throws StorageQueryException {
        try {
            PasswordlessQueries.deleteUser(this, userId);
        } catch (StorageTransactionLogicException e) {
            throw new StorageQueryException(e.actualException);
        }
    }

    @Override
    public PasswordlessDevice getDevice(String deviceIdHash) throws StorageQueryException {
        try {
            return PasswordlessQueries.getDevice(this, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessDevice[] getDevicesByEmail(String email) throws StorageQueryException {
        try {
            return PasswordlessQueries.getDevicesByEmail(this, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessDevice[] getDevicesByPhoneNumber(String phoneNumber) throws StorageQueryException {
        try {
            return PasswordlessQueries.getDevicesByPhoneNumber(this, phoneNumber);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessCode[] getCodesOfDevice(String deviceIdHash) throws StorageQueryException {
        try {
            return PasswordlessQueries.getCodesOfDevice(this, deviceIdHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessCode[] getCodesBefore(long time) throws StorageQueryException {
        try {
            return PasswordlessQueries.getCodesBefore(this, time);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessCode getCode(String codeId) throws StorageQueryException {
        try {
            return PasswordlessQueries.getCode(this, codeId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public PasswordlessCode getCodeByLinkCodeHash(String linkCodeHash) throws StorageQueryException {
        try {
            return PasswordlessQueries.getCodeByLinkCodeHash(this, linkCodeHash);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.passwordless.UserInfo getUserById(String userId)
            throws StorageQueryException {
        try {
            return PasswordlessQueries.getUserById(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.passwordless.UserInfo getUserByEmail(String email)
            throws StorageQueryException {
        try {
            return PasswordlessQueries.getUserByEmail(this, email);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public io.supertokens.pluginInterface.passwordless.UserInfo getUserByPhoneNumber(String phoneNumber)
            throws StorageQueryException {
        try {
            return PasswordlessQueries.getUserByPhoneNumber(this, phoneNumber);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public JsonObject getUserMetadata(String userId) throws StorageQueryException {
        try {
            return UserMetadataQueries.getUserMetadata(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public JsonObject getUserMetadata_Transaction(TransactionConnection con, String userId)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return UserMetadataQueries.getUserMetadata_Transaction(this, sqlCon, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public int setUserMetadata_Transaction(TransactionConnection con, String userId, JsonObject metadata)
            throws StorageQueryException {
        Connection sqlCon = (Connection) con.getConnection();
        try {
            return UserMetadataQueries.setUserMetadata_Transaction(this, sqlCon, userId, metadata);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }

    @Override
    public int deleteUserMetadata(String userId) throws StorageQueryException {
        try {
            return UserMetadataQueries.deleteUserMetadata(this, userId);
        } catch (SQLException e) {
            throw new StorageQueryException(e);
        }
    }
}