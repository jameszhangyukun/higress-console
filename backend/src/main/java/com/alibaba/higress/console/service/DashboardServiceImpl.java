/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.console.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.higress.console.client.grafana.GrafanaClient;
import com.alibaba.higress.console.client.grafana.models.Datasource;
import com.alibaba.higress.console.client.grafana.models.DatasourceCreationResult;
import com.alibaba.higress.console.client.grafana.models.GrafanaDashboard;
import com.alibaba.higress.console.client.grafana.models.GrafanaSearchResult;
import com.alibaba.higress.console.client.grafana.models.SearchType;
import com.alibaba.higress.console.constant.CommonKey;
import com.alibaba.higress.console.controller.dto.DashboardInfo;
import com.alibaba.higress.console.controller.exception.BusinessException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * @author CH3CHO
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String DATASOURCE_UID_PLACEHOLDER = "${datasource.id}";
    private static final String DASHBOARD_DATA_PATH = "/dashboard/main.json";
    private static final String DATASOURCE_TYPE = "prometheus";
    private static final String DATASOURCE_ACCESS = "proxy";

    private static final ExecutorService EXECUTOR =
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("DashboardService-Initializer-%d").build());

    @Value("${" + CommonKey.DASHBOARD_BASE_URL_KEY + ":" + CommonKey.DASHBOARD_BASE_URL_DEFAULT + "}")
    private String apiBaseUrl;

    @Value("${" + CommonKey.DASHBOARD_USERNAME_KEY + ":" + CommonKey.DASHBOARD_USERNAME_DEFAULT + "}")
    private String username = CommonKey.DASHBOARD_USERNAME_DEFAULT;

    @Value("${" + CommonKey.DASHBOARD_PASSWORD_KEY + ":" + CommonKey.DASHBOARD_PASSWORD_DEFAULT + "}")
    private String password = CommonKey.DASHBOARD_PASSWORD_DEFAULT;

    @Value("${" + CommonKey.DASHBOARD_DATASOURCE_NAME_KEY + ":" + CommonKey.DASHBOARD_DATASOURCE_NAME_DEFAULT + "}")
    private String datasourceName = CommonKey.DASHBOARD_DATASOURCE_NAME_DEFAULT;

    @Value("${" + CommonKey.DASHBOARD_DATASOURCE_URL_KEY + ":" + CommonKey.DASHBOARD_DATASOURCE_URL_DEFAULT + "}")
    private String datasourceUrl;

    private GrafanaClient grafanaClient;

    private String dashboardConfiguration;
    private GrafanaDashboard configuredDashboard;

    @PostConstruct
    public void initialize() {
        grafanaClient = new GrafanaClient(apiBaseUrl, username, password);
        try {
            dashboardConfiguration = IOUtils.resourceToString(DASHBOARD_DATA_PATH, StandardCharsets.UTF_8);
            configuredDashboard = GrafanaClient.parseDashboardData(dashboardConfiguration);
        } catch (IOException e) {
            throw new IllegalStateException("Error occurs when loading dashboard data from resource.", e);
        }

        EXECUTOR.submit(new DashboardInitializer());
    }

    @Override
    public DashboardInfo getDashboardInfo() {
        List<GrafanaSearchResult> results;
        try {
            results = grafanaClient.search(null, SearchType.DB, null, null);
        } catch (IOException e) {
            throw new BusinessException("Error occurs when loading dashboard info from Grafana.", e);
        }
        if (CollectionUtils.isEmpty(results)) {
            return null;
        }
        String expectedTitle = configuredDashboard.getTitle();
        if (StringUtils.isEmpty(expectedTitle)) {
            throw new IllegalStateException("No title is found in the configured dashboard.");
        }
        Optional<GrafanaSearchResult> result =
            results.stream().filter(r -> expectedTitle.equals(r.getTitle())).findFirst();
        return result.map(r -> new DashboardInfo(r.getUid(), r.getUrl())).orElse(null);
    }

    @Override
    public void initializeDashboard(boolean overwrite) {
        DashboardInfo dashboardInfo = getDashboardInfo();
        if (!overwrite && (dashboardInfo == null || StringUtils.isEmpty(dashboardInfo.getUrl()))) {
            return;
        }
        List<Datasource> datasources;
        try {
            datasources = grafanaClient.getDatasources();
        } catch (IOException e) {
            throw new BusinessException("Error occurs when loading datasources from Grafana.", e);
        }

        String datasourceUid = null;
        if (CollectionUtils.isNotEmpty(datasources)) {
            datasourceUid = datasources.stream().filter(ds -> datasourceName.equals(ds.getName())).findFirst()
                .map(Datasource::getUid).orElse(null);
        }
        if (datasourceUid == null) {
            Datasource datasource = new Datasource();
            datasource.setType(DATASOURCE_TYPE);
            datasource.setName(datasourceName);
            datasource.setUrl(datasourceUrl);
            datasource.setAccess(DATASOURCE_ACCESS);
            try {
                DatasourceCreationResult result = grafanaClient.createDatasource(datasource);
                if (result.getDatasource() == null) {
                    throw new BusinessException("Creating data source call returns success but no datasource object."
                        + " Message=" + result.getMessage());
                }
                datasourceUid = result.getDatasource().getUid();
            } catch (IOException e) {
                throw new BusinessException("Error occurs when creating Higress datasource info in Grafana.", e);
            }
        }

        String dashboardData = dashboardConfiguration.replace(DATASOURCE_UID_PLACEHOLDER, datasourceUid);
        GrafanaDashboard dashboard;
        try {
            dashboard = GrafanaClient.parseDashboardData(dashboardData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            if (dashboardInfo != null) {
                String uid = dashboardInfo.getUid();
                GrafanaDashboard existedDashboard = grafanaClient.getDashboard(uid);
                if (existedDashboard != null) {
                    dashboard.setId(existedDashboard.getId());
                    dashboard.setUid(uid);
                    dashboard.setVersion(existedDashboard.getVersion());
                }
            }
            if (dashboard.getId() == null) {
                grafanaClient.createDashboard(dashboard);
            } else {
                grafanaClient.updateDashboard(dashboard);
            }
        } catch (IOException e) {
            throw new BusinessException("Error occurs when creating Higress dashboard in Grafana.", e);
        }
    }

    private class DashboardInitializer implements Runnable {

        @Override
        public void run() {
            try {
                initializeDashboard(false);
            } catch (Exception ex) {
                log.error("Error occurs when trying to initialize the dashboard.", ex);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    log.warn("Initialization thread is interrupted.", e);
                }
                // Try again.
                EXECUTOR.submit(this);
            }
        }
    }
}
