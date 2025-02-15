/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.timelineservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.http.HttpConfig;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.minikdc.MiniKdc;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.KerberosTestUtils;
import org.apache.hadoop.security.authentication.server.KerberosAuthenticationHandler;
import org.apache.hadoop.security.ssl.KeyStoreTestUtil;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenSecretManager;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.CollectorInfo;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.client.api.TimelineV2Client;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.security.client.TimelineDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.api.CollectorNodemanagerProtocol;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextResponse;
import org.apache.hadoop.yarn.server.timelineservice.collector.AppLevelTimelineCollector;
import org.apache.hadoop.yarn.server.timelineservice.collector.NodeTimelineCollectorManager;
import org.apache.hadoop.yarn.server.timelineservice.collector.PerNodeTimelineCollectorsAuxService;
import org.apache.hadoop.yarn.server.timelineservice.storage.FileSystemTimelineReaderImpl;
import org.apache.hadoop.yarn.server.timelineservice.storage.FileSystemTimelineWriterImpl;
import org.apache.hadoop.yarn.server.timelineservice.storage.TimelineWriter;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.TIMELINE_HTTP_AUTH_PREFIX;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests timeline authentication filter based security for timeline service v2.
 */
public class TestTimelineAuthFilterForV2 {

  private static final String FOO_USER = "foo";
  private static final String HTTP_USER = "HTTP";
  private static final File TEST_ROOT_DIR = new File(
      System.getProperty("test.build.dir", "target" + File.separator +
          "test-dir"), UUID.randomUUID().toString());
  private static final String BASEDIR =
      System.getProperty("test.build.dir", "target/test-dir") + "/"
          + TestTimelineAuthFilterForV2.class.getSimpleName();
  private static File httpSpnegoKeytabFile = new File(KerberosTestUtils.
      getKeytabFile());
  private static String httpSpnegoPrincipal = KerberosTestUtils.
      getServerPrincipal();
  private static final String ENTITY_TYPE = "dummy_type";
  private static final AtomicInteger ENTITY_TYPE_SUFFIX = new AtomicInteger(0);

  // First param indicates whether HTTPS access or HTTP access and second param
  // indicates whether it is kerberos access or token based access.
  public static Collection<Object[]> params() {
    return Arrays.asList(new Object[][]{{false, true}, {false, false},
        {true, false}, {true, true}});
  }

  private static MiniKdc testMiniKDC;
  private static String keystoresDir;
  private static String sslConfDir;
  private static Configuration conf;
  private static UserGroupInformation nonKerberosUser;

  static {
    try {
      nonKerberosUser = UserGroupInformation.getCurrentUser();
    } catch (IOException e) {
    }
  }

  // Indicates whether HTTPS or HTTP access.
  private boolean withSsl;
  // Indicates whether Kerberos based login is used or token based access is
  // done.
  private boolean withKerberosLogin;
  private NodeTimelineCollectorManager collectorManager;
  private PerNodeTimelineCollectorsAuxService auxService;

  public void initTestTimelineAuthFilterForV2(boolean withSsl,
      boolean withKerberosLogin) {
    this.withSsl = withSsl;
    this.withKerberosLogin = withKerberosLogin;
  }

  @BeforeAll
  public static void setup() {
    try {
      testMiniKDC = new MiniKdc(MiniKdc.createConf(), TEST_ROOT_DIR);
      testMiniKDC.start();
      testMiniKDC.createPrincipal(
          httpSpnegoKeytabFile, HTTP_USER + "/localhost");
    } catch (Exception e) {
      fail("Couldn't setup MiniKDC.");
    }

    // Setup timeline service v2.
    try {
      conf = new Configuration(false);
      conf.setClass("fs.file.impl", RawLocalFileSystem.class,
          FileSystem.class);
      conf.setStrings(TIMELINE_HTTP_AUTH_PREFIX + "type",
          "kerberos");
      conf.set(TIMELINE_HTTP_AUTH_PREFIX +
          KerberosAuthenticationHandler.PRINCIPAL, httpSpnegoPrincipal);
      conf.set(TIMELINE_HTTP_AUTH_PREFIX +
          KerberosAuthenticationHandler.KEYTAB,
          httpSpnegoKeytabFile.getAbsolutePath());
      conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
          "kerberos");
      conf.set(YarnConfiguration.TIMELINE_SERVICE_PRINCIPAL,
          httpSpnegoPrincipal);
      conf.set(YarnConfiguration.TIMELINE_SERVICE_KEYTAB,
          httpSpnegoKeytabFile.getAbsolutePath());
      // Enable timeline service v2
      conf.setBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED, true);
      conf.setFloat(YarnConfiguration.TIMELINE_SERVICE_VERSION, 2.0f);
      conf.setClass(YarnConfiguration.TIMELINE_SERVICE_WRITER_CLASS,
          FileSystemTimelineWriterImpl.class, TimelineWriter.class);
      conf.set(YarnConfiguration.TIMELINE_SERVICE_READER_BIND_HOST,
          "localhost");
      conf.set(FileSystemTimelineWriterImpl.TIMELINE_SERVICE_STORAGE_DIR_ROOT,
          TEST_ROOT_DIR.getAbsolutePath());
      conf.set("hadoop.proxyuser.HTTP.hosts", "*");
      conf.set("hadoop.proxyuser.HTTP.users", FOO_USER);
      UserGroupInformation.setConfiguration(conf);
    } catch (Exception e) {
      fail("Couldn't setup TimelineServer V2.");
    }
  }

  @BeforeEach
  public void initialize() throws Exception {
    if (withSsl) {
      conf.set(YarnConfiguration.YARN_HTTP_POLICY_KEY,
          HttpConfig.Policy.HTTPS_ONLY.name());
      File base = new File(BASEDIR);
      FileUtil.fullyDelete(base);
      base.mkdirs();
      keystoresDir = new File(BASEDIR).getAbsolutePath();
      sslConfDir =
          KeyStoreTestUtil.getClasspathDir(TestTimelineAuthFilterForV2.class);
      KeyStoreTestUtil.setupSSLConfig(keystoresDir, sslConfDir, conf, false);
    } else  {
      conf.set(YarnConfiguration.YARN_HTTP_POLICY_KEY,
          HttpConfig.Policy.HTTP_ONLY.name());
    }
    if (!withKerberosLogin) {
      // For timeline delegation token based access, set delegation token renew
      // interval to 100 ms. to test if timeline delegation token for the app is
      // renewed automatically if app is still alive.
      conf.setLong(
          YarnConfiguration.TIMELINE_DELEGATION_TOKEN_RENEW_INTERVAL, 100);
      // Set token max lifetime to 4 seconds to test if timeline delegation
      // token for the app is regenerated automatically if app is still alive.
      conf.setLong(
          YarnConfiguration.TIMELINE_DELEGATION_TOKEN_MAX_LIFETIME, 4000);
    }
    UserGroupInformation.setConfiguration(conf);
    collectorManager = new DummyNodeTimelineCollectorManager();
    PerNodeTimelineCollectorsAuxService as = new PerNodeTimelineCollectorsAuxService();
    auxService = as.launchServer(
        new String[0], collectorManager, conf);
    if (withKerberosLogin) {
      SecurityUtil.login(conf, YarnConfiguration.TIMELINE_SERVICE_KEYTAB,
          YarnConfiguration.TIMELINE_SERVICE_PRINCIPAL, "localhost");
    }
    ApplicationId appId = ApplicationId.newInstance(0, 1);
    auxService.addApplicationIfAbsent(
        appId, UserGroupInformation.getCurrentUser().getUserName());
    if (!withKerberosLogin) {
      AppLevelTimelineCollector collector =
          (AppLevelTimelineCollector) collectorManager.get(appId);
      Token<TimelineDelegationTokenIdentifier> token =
          collector.getDelegationTokenForApp();
      token.setService(new Text("localhost" + token.getService().toString().
          substring(token.getService().toString().indexOf(":"))));
      UserGroupInformation.getCurrentUser().addToken(token);
    }
  }

  private TimelineV2Client createTimelineClientForUGI(ApplicationId appId) {
    TimelineV2Client client =
        TimelineV2Client.createTimelineClient(ApplicationId.newInstance(0, 1));
    // set the timeline service address.
    String restBindAddr = collectorManager.getRestServerBindAddress();
    String addr =
        "localhost" + restBindAddr.substring(restBindAddr.indexOf(":"));
    client.setTimelineCollectorInfo(CollectorInfo.newInstance(addr));
    client.init(conf);
    client.start();
    return client;
  }

  @AfterAll
  public static void tearDown() throws Exception {
    if (testMiniKDC != null) {
      testMiniKDC.stop();
    }
    FileUtil.fullyDelete(TEST_ROOT_DIR);
  }

  @AfterEach
  public void destroy() throws Exception {
    if (auxService != null) {
      auxService.stop();
    }
    if (withSsl) {
      KeyStoreTestUtil.cleanupSSLConfig(keystoresDir, sslConfDir);
      FileUtil.fullyDelete(new File(BASEDIR));
    }
    if (withKerberosLogin) {
      UserGroupInformation.getCurrentUser().logoutUserFromKeytab();
    }
    // Reset the user for next run.
    UserGroupInformation.setLoginUser(
        UserGroupInformation.createRemoteUser(nonKerberosUser.getUserName()));
  }

  private static TimelineEntity createEntity(String id, String type) {
    TimelineEntity entityToStore = new TimelineEntity();
    entityToStore.setId(id);
    entityToStore.setType(type);
    entityToStore.setCreatedTime(0L);
    return entityToStore;
  }

  private static void verifyEntity(File entityTypeDir, String id, String type)
      throws InterruptedException, IOException {
    File entityFile = new File(entityTypeDir, id +
        FileSystemTimelineWriterImpl.TIMELINE_SERVICE_STORAGE_EXTENSION);
    TimelineEntity entity = null;
    for (int i = 0; i < 50; i++) {
      if (entityFile.exists()) {
        entity = readEntityFile(entityFile);
        if (entity != null) {
          break;
        }
      }
      Thread.sleep(50);
    }
    assertTrue(entityFile.exists());
    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals(type, entity.getType());
  }

  private static TimelineEntity readEntityFile(File entityFile)
      throws IOException {
    BufferedReader reader = null;
    String strLine;
    try {
      reader = new BufferedReader(new FileReader(entityFile));
      while ((strLine = reader.readLine()) != null) {
        if (strLine.trim().length() > 0) {
          return FileSystemTimelineReaderImpl.
              getTimelineRecordFromJSON(strLine.trim(), TimelineEntity.class);
        }
      }
      return null;
    } finally {
      reader.close();
    }
  }

  private void publishAndVerifyEntity(ApplicationId appId, File entityTypeDir,
      String entityType, int numEntities) throws Exception {
    TimelineV2Client client = createTimelineClientForUGI(appId);
    try {
      // Sync call. Results available immediately.
      client.putEntities(createEntity("entity1", entityType));
      assertEquals(numEntities, entityTypeDir.listFiles().length);
      verifyEntity(entityTypeDir, "entity1", entityType);
      // Async call.
      client.putEntitiesAsync(createEntity("entity2", entityType));
    } finally {
      client.stop();
    }
  }

  private boolean publishWithRetries(ApplicationId appId, File entityTypeDir,
      String entityType, int numEntities) throws Exception {
    for (int i = 0; i < 10; i++) {
      try {
        publishAndVerifyEntity(appId, entityTypeDir, entityType, numEntities);
      } catch (YarnException e) {
        Thread.sleep(50);
        continue;
      }
      return true;
    }
    return false;
  }

  @MethodSource("params")
  @ParameterizedTest
  void testPutTimelineEntities(boolean withSsl, boolean withKerberosLogin) throws Exception {
    initTestTimelineAuthFilterForV2(withSsl, withKerberosLogin);
    final String entityType = ENTITY_TYPE +
        ENTITY_TYPE_SUFFIX.getAndIncrement();
    ApplicationId appId = ApplicationId.newInstance(0, 1);
    File entityTypeDir = new File(TEST_ROOT_DIR.getAbsolutePath() +
        File.separator + "entities" + File.separator +
        YarnConfiguration.DEFAULT_RM_CLUSTER_ID + File.separator +
        UserGroupInformation.getCurrentUser().getUserName() +
        File.separator + "test_flow_name" + File.separator +
        "test_flow_version" + File.separator + "1" + File.separator +
        appId.toString() + File.separator + entityType);
    if (withKerberosLogin) {
      KerberosTestUtils.doAs(HTTP_USER + "/localhost", new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          publishAndVerifyEntity(appId, entityTypeDir, entityType, 1);
          return null;
        }
      });
    } else {
      assertTrue(publishWithRetries(appId, entityTypeDir, entityType, 1),
          "Entities should have been published successfully.");

      AppLevelTimelineCollector collector =
          (AppLevelTimelineCollector) collectorManager.get(appId);
      Token<TimelineDelegationTokenIdentifier> token =
          collector.getDelegationTokenForApp();
      assertNotNull(token);

      // Verify if token is renewed automatically and entities can still be
      // published.
      Thread.sleep(1000);
      // Entities should publish successfully after renewal.
      assertTrue(publishWithRetries(appId, entityTypeDir, entityType, 2),
          "Entities should have been published successfully.");
      assertNotNull(collector);
      verify(collectorManager.getTokenManagerService(), atLeastOnce()).
          renewToken(eq(collector.getDelegationTokenForApp()),
          any(String.class));

      // Wait to ensure lifetime of token expires and ensure its regenerated
      // automatically.
      Thread.sleep(3000);
      for (int i = 0; i < 40; i++) {
        if (!token.equals(collector.getDelegationTokenForApp())) {
          break;
        }
        Thread.sleep(50);
      }
      assertNotEquals(token,
          collector.getDelegationTokenForApp(),
          "Token should have been regenerated.");
      Thread.sleep(1000);
      // Try publishing with the old token in UGI. Publishing should fail due
      // to invalid token.
      try {
        publishAndVerifyEntity(appId, entityTypeDir, entityType, 2);
        fail("Exception should have been thrown due to Invalid Token.");
      } catch (YarnException e) {
        assertTrue(e.getCause().getMessage().contains("InvalidToken"),
            "Exception thrown should have been due to Invalid Token.");
      }

      // Update the regenerated token in UGI and retry publishing entities.
      Token<TimelineDelegationTokenIdentifier> regeneratedToken =
          collector.getDelegationTokenForApp();
      regeneratedToken.setService(new Text("localhost" +
          regeneratedToken.getService().toString().substring(
              regeneratedToken.getService().toString().indexOf(":"))));
      UserGroupInformation.getCurrentUser().addToken(regeneratedToken);
      assertTrue(publishWithRetries(appId, entityTypeDir, entityType, 2),
          "Entities should have been published successfully.");
      // Token was generated twice, once when app collector was created and
      // later after token lifetime expiry.
      verify(collectorManager.getTokenManagerService(), times(2)).
          generateToken(any(UserGroupInformation.class), any(String.class));
      assertEquals(1, ((DummyNodeTimelineCollectorManager) collectorManager).
          getTokenExpiredCnt());
    }
    // Wait for async entity to be published.
    FileFilter tmpFilter = (pathname -> !pathname.getName().endsWith(".tmp"));
    File[] entities = null;
    for (int i = 0; i < 50; i++) {
      entities = entityTypeDir.listFiles(tmpFilter);
      if (entities != null && entities.length == 2) {
        break;
      }
      Thread.sleep(50);
    }
    assertNotNull(entities, "Error reading entityTypeDir");
    assertEquals(2, entities.length);
    verifyEntity(entityTypeDir, "entity2", entityType);
    AppLevelTimelineCollector collector =
        (AppLevelTimelineCollector) collectorManager.get(appId);
    assertNotNull(collector);
    auxService.removeApplication(appId);
    verify(collectorManager.getTokenManagerService()).cancelToken(
        eq(collector.getDelegationTokenForApp()), any(String.class));
  }

  private static class DummyNodeTimelineCollectorManager extends
      NodeTimelineCollectorManager {
    private volatile int tokenExpiredCnt = 0;

    private int getTokenExpiredCnt() {
      return tokenExpiredCnt;
    }

    @Override
    protected TimelineV2DelegationTokenSecretManagerService createTokenManagerService() {
      return spy(new TimelineV2DelegationTokenSecretManagerService() {
        @Override
        protected AbstractDelegationTokenSecretManager
            <TimelineDelegationTokenIdentifier> createTimelineDelegationTokenSecretManager(long
            secretKeyInterval,
            long tokenMaxLifetime, long tokenRenewInterval,
            long tokenRemovalScanInterval) {
          return spy(new TimelineV2DelegationTokenSecretManager(
              secretKeyInterval, tokenMaxLifetime, tokenRenewInterval, 2000L) {
            @Override
            protected void logExpireToken(
                TimelineDelegationTokenIdentifier ident) throws IOException {
              tokenExpiredCnt++;
            }
          });
        }
      });
    }

    @Override
    protected CollectorNodemanagerProtocol getNMCollectorService() {
      CollectorNodemanagerProtocol protocol =
          mock(CollectorNodemanagerProtocol.class);
      try {
        GetTimelineCollectorContextResponse response =
            GetTimelineCollectorContextResponse.newInstance(
                UserGroupInformation.getCurrentUser().getUserName(),
                "test_flow_name", "test_flow_version", 1L);
        when(protocol.getTimelineCollectorContext(any(
            GetTimelineCollectorContextRequest.class))).thenReturn(response);
      } catch (YarnException | IOException e) {
        fail();
      }
      return protocol;
    }
  }
}