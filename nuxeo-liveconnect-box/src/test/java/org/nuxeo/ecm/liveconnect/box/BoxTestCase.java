/*
 * (C) Copyright 2015-2016 Nuxeo SA (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Kevin Leturc
 */
package org.nuxeo.ecm.liveconnect.box;

import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;

@Ignore
@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy({ "org.nuxeo.ecm.core.cache", "org.nuxeo.ecm.core.mimetype", "org.nuxeo.ecm.platform.oauth" })
@LocalDeploy({ "org.nuxeo.ecm.platform.query.api:OSGI-INF/pageprovider-framework.xml",
        "org.nuxeo.ecm.liveconnect.box:OSGI-INF/cache-config.xml",
        "org.nuxeo.ecm.liveconnect.box:OSGI-INF/test-box-config.xml",
        "org.nuxeo.ecm.liveconnect:OSGI-INF/liveconnect-workmanager-contrib.xml",
        "org.nuxeo.ecm.liveconnect.box:OSGI-INF/box-pageprovider-contrib.xml" })
public class BoxTestCase {

    protected static final String USERID = "tester@example.com";

}