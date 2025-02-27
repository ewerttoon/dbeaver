/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
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
package org.jkiss.dbeaver.ext.clickhouse.model.data;

import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.data.DBDFormatSettings;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.data.DBDValueHandlerProvider;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;

public class ClickhouseValueHandlerProvider implements DBDValueHandlerProvider {
    @Override
    public DBDValueHandler getValueHandler(DBPDataSource dataSource, DBDFormatSettings preferences, DBSTypedObject type) {
        if (type.getTypeName().equalsIgnoreCase("enum8") || type.getTypeName().equalsIgnoreCase("enum16")) {
            return ClickhouseEnumValueHandler.INSTANCE;
        } else if (type.getDataKind() == DBPDataKind.ARRAY) {
            return ClickhouseArrayValueHandler.INSTANCE;
        } else if (type.getDataKind() == DBPDataKind.STRUCT) {
            return ClickhouseStructValueHandler.INSTANCE;
        } else {
            return null;
        }
    }
}
