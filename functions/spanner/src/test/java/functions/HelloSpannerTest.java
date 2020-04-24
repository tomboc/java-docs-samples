/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package functions;

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.common.testing.TestLogHandler;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.logging.Logger;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;

@RunWith(JUnit4.class)
public class HelloSpannerTest {
  @Mock private HttpRequest request;
  @Mock private HttpResponse response;
  @Mock private DatabaseClient client;

  private static final Logger LOGGER = Logger.getLogger(HelloSpanner.class.getName());
  private static final TestLogHandler logHandler = new TestLogHandler();
  private static boolean originalUseParentHandlers;

  private BufferedWriter writerOut;
  private StringWriter responseOut;

  @BeforeClass
  public static void setupTestLogging() {
    LOGGER.addHandler(logHandler);
    originalUseParentHandlers = LOGGER.getUseParentHandlers();
    LOGGER.setUseParentHandlers(false);
  }

  @AfterClass
  public static void restoreLogging() {
    LOGGER.removeHandler(logHandler);
    LOGGER.setUseParentHandlers(originalUseParentHandlers);
  }

  @Before
  public void beforeTest() throws IOException {
    Mockito.mockitoSession().initMocks(this);

    request = PowerMockito.mock(HttpRequest.class);
    response = PowerMockito.mock(HttpResponse.class);
    client = PowerMockito.mock(DatabaseClient.class);

    responseOut = new StringWriter();
    writerOut = new BufferedWriter(responseOut);
    PowerMockito.when(response.getWriter()).thenReturn(writerOut);

    logHandler.clear();
  }

  private void setupSuccessfulMockQuery() {
    ReadContext readContext = PowerMockito.mock(ReadContext.class);
    ResultSet resultSet = PowerMockito.mock(ResultSet.class);
    PowerMockito.when(resultSet.next()).thenReturn(true, true, false);
    PowerMockito.when(resultSet.getLong("SingerId")).thenReturn(1L, 2L, 0L);
    PowerMockito.when(resultSet.getLong("AlbumId")).thenReturn(1L, 1L, 0L);
    PowerMockito.when(resultSet.getString("AlbumTitle")).thenReturn("Album 1", "Album 2", null);
    PowerMockito.when(
            readContext.executeQuery(
                Statement.of("SELECT SingerId, AlbumId, AlbumTitle FROM Albums")))
        .thenReturn(resultSet);
    PowerMockito.when(client.singleUse()).thenReturn(readContext);
  }

  @Test
  public void functionsHelloSpanner_shouldListAlbums() throws Exception {
    setupSuccessfulMockQuery();
    new HelloSpanner() {
      @Override
      DatabaseClient getClient() {
        return client;
      }
    }.service(request, response);
    writerOut.flush();
    assertThat(responseOut.toString()).isEqualTo("Albums:\n1 1 Album 1\n2 1 Album 2\n");
  }

  private void setupFailedMockQuery() {
    ReadContext readContext = PowerMockito.mock(ReadContext.class);
    PowerMockito.when(
            readContext.executeQuery(
                Statement.of("SELECT SingerId, AlbumId, AlbumTitle FROM Albums")))
        .thenThrow(
            SpannerExceptionFactory.newSpannerException(
                ErrorCode.NOT_FOUND, "Table `Albums` not found"));
    PowerMockito.when(client.singleUse()).thenReturn(readContext);
  }

  @Test
  public void functionsHelloSpanner_shouldShowQueryError() throws Exception {
    setupFailedMockQuery();
    new HelloSpanner() {
      @Override
      DatabaseClient getClient() {
        return client;
      }
    }.service(request, response);
    writerOut.flush();
    assertThat(responseOut.toString())
        .isEqualTo("Error querying database: NOT_FOUND: Table `Albums` not found\n");
  }

  @Test
  public void functionsHelloSpanner_shouldShowInitializationError() throws Exception {
    new HelloSpanner() {
      @Override
      DatabaseClient getClient() {
        throw new IllegalArgumentException("Invalid database name");
      }
    }.service(request, response);
    writerOut.flush();
    assertThat(responseOut.toString())
        .isEqualTo("Error setting up Spanner: Invalid database name\n");
    assertThat(logHandler.getStoredLogRecords().get(0).getMessage())
        .startsWith("Spanner example failed");
  }
}
