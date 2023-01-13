/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.vfs2.provider.sftp;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.UserAuthenticator;
import org.apache.commons.vfs2.util.Os;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.io.File;
import java.util.Properties;

/**
 * Create a JSch Session instance.
 */
public final class SftpClientFactory {
    private static final String SSH_DIR_NAME = ".ssh";

    private static final Log LOG = LogFactory.getLog(SftpClientFactory.class);

    static {
        JSch.setLogger(new JSchLogger());
    }

    private SftpClientFactory() {
    }

    /**
     * Creates a new connection to the server.
     *
     * @param hostname The name of the host to connect to.
     * @param port The port to use.
     * @param username The user's id.
     * @param password The user's password.
     * @param fileSystemOptions The FileSystem options.
     * @return A Session.
     * @throws FileSystemException if an error occurs.
     */
    public static Session createConnection(final String hostname, final int port, final char[] username,
            final char[] password, final FileSystemOptions fileSystemOptions) throws FileSystemException {
        JSch.setConfig("kex", JSch.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1");
        JSch.setConfig("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa,ssh-dss");
        JSch.setConfig("cipher.s2c", JSch.getConfig("cipher.s2c") + ",aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-cbc,aes256-cbc");
        JSch.setConfig("cipher.c2s", JSch.getConfig("cipher.c2s") + ",aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc,aes192-cbc,aes256-cbc");
        JSch.setConfig("mac.s2c", JSch.getConfig("mac.s2c") + ",hmac-md5,hmac-sha1,hmac-sha1-96,hmac-md5-96");
        JSch.setConfig("mac.c2s", JSch.getConfig("mac.c2s") + ",hmac-md5,hmac-sha1,hmac-sha1-96,hmac-md5-96");
        JSch.setConfig("PubkeyAcceptedAlgorithms", JSch.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa,ssh-dss");
        final JSch jsch = new JSch();

        File sshDir = null;

        // new style - user passed
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        final File knownHostsFile = builder.getKnownHosts(fileSystemOptions);
        final IdentityInfo[] identities = builder.getIdentityInfo(fileSystemOptions);
        String passPhrase = builder.getIdentityPassPhrase(fileSystemOptions);
        final IdentityRepositoryFactory repositoryFactory = builder.getIdentityRepositoryFactory(fileSystemOptions);

        sshDir = findSshDir();

        setKnownHosts(jsch, sshDir, knownHostsFile);

        if (repositoryFactory != null) {
            jsch.setIdentityRepository(repositoryFactory.create(jsch));
        }

        addIdentities(jsch, sshDir, identities, passPhrase);

        UserAuthenticationData proxyAuthData = null;

        Session session;
        try {
            session = jsch.getSession(new String(username), hostname, port);
            if (password != null) {
                session.setPassword(new String(password));
            }

            final Integer timeout = builder.getTimeout(fileSystemOptions);
            if (timeout != null) {
                session.setTimeout(timeout.intValue());
            }

            final UserInfo userInfo = builder.getUserInfo(fileSystemOptions);
            if (userInfo != null) {
                session.setUserInfo(userInfo);
            }

            final Properties config = new Properties();

            // set StrictHostKeyChecking property
            final String strictHostKeyChecking = builder.getStrictHostKeyChecking(fileSystemOptions);
            if (strictHostKeyChecking != null) {
                config.setProperty("StrictHostKeyChecking", strictHostKeyChecking);
            }
            // set PreferredAuthentications property
            final String preferredAuthentications = builder.getPreferredAuthentications(fileSystemOptions);
            if (preferredAuthentications != null) {
                config.setProperty("PreferredAuthentications", preferredAuthentications);
            }

            // set compression property
            final String compression = builder.getCompression(fileSystemOptions);
            if (compression != null) {
                config.setProperty("compression.s2c", compression);
                config.setProperty("compression.c2s", compression);
            }

            final String proxyHost = builder.getProxyHost(fileSystemOptions);
            if (proxyHost != null) {
                final int proxyPort = builder.getProxyPort(fileSystemOptions);
                final SftpFileSystemConfigBuilder.ProxyType proxyType = builder.getProxyType(fileSystemOptions);
                Proxy proxy = null;
                UserAuthenticator proxyAuth = SftpFileSystemConfigBuilder.getInstance().getProxyUserAuthenticator
                        (fileSystemOptions);
                char[] proxyUsername = null, proxyPassword = null;
                if (proxyAuth != null) {
                    proxyAuthData = UserAuthenticatorUtils.authenticate(proxyAuth, SftpFileProvider
                            .AUTHENTICATOR_TYPES);
                    proxyUsername = UserAuthenticatorUtils.getData(proxyAuthData, UserAuthenticationData.USERNAME,
                            null);
                    proxyPassword = UserAuthenticatorUtils.getData(proxyAuthData, UserAuthenticationData.PASSWORD,
                            null);
                }

                if (SftpFileSystemConfigBuilder.PROXY_HTTP.equals(proxyType)) {
                    proxy = createProxyHTTP(proxyHost, proxyPort, proxyUsername, proxyPassword);
                } else if (SftpFileSystemConfigBuilder.PROXY_SOCKS5.equals(proxyType)) {
                    proxy = createProxySOCKS5(proxyHost, proxyPort, proxyUsername, proxyPassword);
                } else if (SftpFileSystemConfigBuilder.PROXY_STREAM.equals(proxyType)) {
                    proxy = createStreamProxy(proxyHost, proxyPort, fileSystemOptions, builder);
                }

                if (proxy != null) {
                    session.setProxy(proxy);
                }
            }

            // set properties for the session
            if (config.size() > 0) {
                session.setConfig(config);
            }
            session.setDaemonThread(true);
            session.connect();
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider.sftp/connect.error", exc, hostname);
        } finally {
            UserAuthenticatorUtils.cleanup(proxyAuthData);
        }

        return session;
    }

    private static void addIdentities(final JSch jsch, final File sshDir, final IdentityInfo[] identities,
                                      String passPhrase)
            throws FileSystemException {
        if (identities != null) {
            for (final IdentityInfo info : identities) {
                addIndentity(jsch, info, passPhrase);
            }
        } else {
            // Load the private key (rsa-key only)
            final File privateKeyFile = new File(sshDir, "id_rsa");
            if (privateKeyFile.isFile() && privateKeyFile.canRead()) {
                addIndentity(jsch, new IdentityInfo(privateKeyFile), passPhrase);
            }
        }
    }

    private static void addIndentity(final JSch jsch, final IdentityInfo info, String passPhrase) throws
            FileSystemException {
        try {
            final String privateKeyFile = info.getPrivateKey() != null ? info.getPrivateKey().getAbsolutePath() : null;
            final String publicKeyFile = info.getPublicKey() != null ? info.getPublicKey().getAbsolutePath() : null;

            if (passPhrase != null) {
                jsch.addIdentity(privateKeyFile, passPhrase);
            } else {
                jsch.addIdentity(privateKeyFile, publicKeyFile, info.getPassPhrase());
            }

        } catch (final JSchException e) {
            throw new FileSystemException("vfs.provider.sftp/load-private-key.error", info, e);
        }
    }

    private static void setKnownHosts(final JSch jsch, final File sshDir, File knownHostsFile)
            throws FileSystemException {
        try {
            if (knownHostsFile != null) {
                jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
            } else {
                // Load the known hosts file
                knownHostsFile = new File(sshDir, "known_hosts");
                if (knownHostsFile.isFile() && knownHostsFile.canRead()) {
                    jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
                }
            }
        } catch (final JSchException e) {
            throw new FileSystemException("vfs.provider.sftp/known-hosts.error", knownHostsFile.getAbsolutePath(), e);
        }

    }

    private static Proxy createStreamProxy(final String proxyHost, final int proxyPort,
            final FileSystemOptions fileSystemOptions, final SftpFileSystemConfigBuilder builder) {
        Proxy proxy;
        // Use a stream proxy, i.e. it will use a remote host as a proxy
        // and run a command (e.g. netcat) that forwards input/output
        // to the target host.

        // Here we get the settings for connecting to the proxy:
        // user, password, options and a command
        final String proxyUser = builder.getProxyUser(fileSystemOptions);
        final String proxyPassword = builder.getProxyPassword(fileSystemOptions);
        final FileSystemOptions proxyOptions = builder.getProxyOptions(fileSystemOptions);

        final String proxyCommand = builder.getProxyCommand(fileSystemOptions);

        // Create the stream proxy
        proxy = new SftpStreamProxy(proxyCommand, proxyUser, proxyHost, proxyPort, proxyPassword, proxyOptions);
        return proxy;
    }

    private static ProxySOCKS5 createProxySOCKS5(final String proxyHost, final int proxyPort, char[] proxyUsername,
                                                 char[] proxyPassword) {
        ProxySOCKS5 proxy = (proxyPort == 0) ? new ProxySOCKS5(proxyHost) : new ProxySOCKS5(proxyHost, proxyPort);
        if (proxyUsername != null && proxyPassword != null) {
            proxy.setUserPasswd(new String(proxyUsername),
                    new String(proxyPassword));
        }
        return proxy;
    }

    private static ProxyHTTP createProxyHTTP(final String proxyHost, final int proxyPort, char[] proxyUsername,
                                             char[] proxyPassword) {
        ProxyHTTP proxy = (proxyPort == 0) ? new ProxyHTTP(proxyHost) : new ProxyHTTP(proxyHost, proxyPort);

        if (proxyUsername != null && proxyPassword != null) {
            proxy.setUserPasswd(new String(proxyUsername),
                    new String(proxyPassword));
        }
        return proxy;
    }

    /**
     * Finds the .ssh directory.
     * <p>
     * The lookup order is:
     * <ol>
     * <li>The system property {@code vfs.sftp.sshdir} (the override mechanism)</li>
     * <li>{user.home}/.ssh</li>
     * <li>On Windows only: C:\cygwin\home\{user.name}\.ssh</li>
     * <li>The current directory, as a last resort.</li>
     * <ol>
     * Windows Notes:<br>
     * The default installation directory for Cygwin is {@code C:\cygwin}. On my set up (Gary here), I have Cygwin in
     * C:\bin\cygwin, not the default. Also, my .ssh directory was created in the {user.home} directory.
     *
     * @return The .ssh directory
     */
    private static File findSshDir() {
        String sshDirPath;
        sshDirPath = System.getProperty("vfs.sftp.sshdir");
        if (sshDirPath != null) {
            final File sshDir = new File(sshDirPath);
            if (sshDir.exists()) {
                return sshDir;
            }
        }

        File sshDir = new File(System.getProperty("user.home"), SSH_DIR_NAME);
        if (sshDir.exists()) {
            return sshDir;
        }

        if (Os.isFamily(Os.OS_FAMILY_WINDOWS)) {
            // TODO - this may not be true
            final String userName = System.getProperty("user.name");
            sshDir = new File("C:\\cygwin\\home\\" + userName + "\\" + SSH_DIR_NAME);
            if (sshDir.exists()) {
                return sshDir;
            }
        }
        return new File("");
    }

    /** Interface JSchLogger with JCL. */
    private static class JSchLogger implements Logger {
        @Override
        public boolean isEnabled(final int level) {
            switch (level) {
            case FATAL:
                return LOG.isFatalEnabled();
            case ERROR:
                return LOG.isErrorEnabled();
            case WARN:
                return LOG.isDebugEnabled();
            case DEBUG:
                return LOG.isDebugEnabled();
            case INFO:
                return LOG.isInfoEnabled();
            default:
                return LOG.isDebugEnabled();

            }
        }

        @Override
        public void log(final int level, final String msg) {
            switch (level) {
            case FATAL:
                LOG.fatal(msg);
                break;
            case ERROR:
                LOG.error(msg);
                break;
            case WARN:
                LOG.warn(msg);
                break;
            case DEBUG:
                LOG.debug(msg);
                break;
            case INFO:
                LOG.info(msg);
                break;
            default:
                LOG.debug(msg);
            }
        }
    }
}
