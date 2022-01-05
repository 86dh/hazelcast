/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.aggregate.function;

import com.hazelcast.jet.sql.impl.schema.HazelcastSqlOperandMetadata;
import com.hazelcast.jet.sql.impl.schema.HazelcastTableFunctionParameter;
import com.hazelcast.jet.sql.impl.validate.HazelcastCallBinding;
import com.hazelcast.jet.sql.impl.validate.HazelcastSqlValidator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;

import java.util.List;

import static com.hazelcast.jet.sql.impl.aggregate.WindowUtils.getOrderingColumnType;

final class WindowOperandMetadata extends HazelcastSqlOperandMetadata {

    private final int[] operandIndexes;

    WindowOperandMetadata(List<HazelcastTableFunctionParameter> parameters, int[] operandIndexes) {
        super(parameters);
        this.operandIndexes = operandIndexes;
    }

    @Override
    protected boolean checkOperandTypes(HazelcastCallBinding binding, boolean throwOnFailure) {
        boolean result = true;
        for (int columnIndex : operandIndexes) {
            SqlNode column = binding.operand(columnIndex);
            result &= checkColumnOperand(binding, column);
        }
        if (!result && throwOnFailure) {
            throw binding.newValidationSignatureError();
        }
        return result;
    }

    private static boolean checkColumnOperand(HazelcastCallBinding binding, SqlNode operand) {
        HazelcastSqlValidator validator = binding.getValidator();
        RelDataType orderingColumnType = getOrderingColumnType(binding, 1);
        return checkColumnType(validator, orderingColumnType.getSqlTypeName(), operand);
    }

    private static boolean checkColumnType(SqlValidator validator, SqlTypeName orderingColumnType, SqlNode lag) {
        SqlTypeName lagType = validator.getValidatedNodeType(lag).getSqlTypeName();
        if (SqlTypeName.INT_TYPES.contains(orderingColumnType)) {
            return SqlTypeName.INT_TYPES.contains(lagType);
        } else if (SqlTypeName.DATETIME_TYPES.contains(orderingColumnType)) {
            return lagType.getFamily() == SqlTypeFamily.INTERVAL_DAY_TIME;
        } else {
            return false;
        }
    }
}