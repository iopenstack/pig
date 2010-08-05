/*
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

package org.apache.pig.newplan.logical.rules;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.pig.newplan.Operator;
import org.apache.pig.newplan.OperatorPlan;
import org.apache.pig.newplan.OperatorSubPlan;
import org.apache.pig.newplan.logical.Util;
import org.apache.pig.newplan.logical.relational.LOFilter;
import org.apache.pig.newplan.logical.relational.LOForEach;
import org.apache.pig.newplan.logical.relational.LOSort;
import org.apache.pig.newplan.logical.relational.LOSplitOutput;
import org.apache.pig.newplan.logical.relational.LogicalPlan;
import org.apache.pig.newplan.logical.relational.LogicalRelationalOperator;
import org.apache.pig.newplan.logical.relational.LogicalSchema;
import org.apache.pig.newplan.optimizer.Transformer;

public class AddForEach extends WholePlanRule {
    protected static final String STATUS = "AddForEach:Status";
    protected static final int STATUSADDED = 1;
    protected static final int STATUSNEWFOREACH = 2;
    
    public AddForEach(String n) {
        super(n);		
    }

    @Override
    public Transformer getNewTransformer() {
        return new AddForEachTransformer();
    }
    
    public class AddForEachTransformer extends Transformer {
        LogicalRelationalOperator opForAdd;
        OperatorSubPlan subPlan;

        @Override
        public boolean check(OperatorPlan matched) throws IOException {
            Iterator<Operator> iter = matched.getOperators();
            while(iter.hasNext()) {
                LogicalRelationalOperator op = (LogicalRelationalOperator)iter.next();
                if ((op instanceof LOFilter||op instanceof LOSort||op instanceof LOSplitOutput) && shouldAdd(op)) {
                    opForAdd = op;
                    return true;
                }
            }
            
            return false;
        }

        @Override
        public OperatorPlan reportChanges() {        	
            return subPlan;
        }

        private void addSuccessors(Operator op) throws IOException {
            subPlan.add(op);
            List<Operator> ll = op.getPlan().getSuccessors(op);
            if (ll != null) {
                for(Operator suc: ll) {
                    addSuccessors(suc);
                }
            }
        }
        
        @Override
        public void transform(OperatorPlan matched) throws IOException {            
            addForeach(opForAdd);
            
            subPlan = new OperatorSubPlan(currentPlan);
            addSuccessors(opForAdd);
        }
        
        @SuppressWarnings("unchecked")
        // check if an LOForEach should be added after the logical operator
        private boolean shouldAdd(LogicalRelationalOperator op) throws IOException {
            Integer status = (Integer)op.getAnnotation(STATUS);
            if (status!=null && (status==STATUSADDED ||status==STATUSNEWFOREACH))
                return false;
            
            Set<Long> outputUids = (Set<Long>)op.getAnnotation(ColumnPruneHelper.OUTPUTUIDS);
            if (outputUids==null)
                return false;
            
            LogicalSchema schema = op.getSchema();
            if (schema==null)
                return false;
            
            Set<Integer> columnsToDrop = new HashSet<Integer>();
            
            for (int i=0;i<schema.size();i++) {
                if (!outputUids.contains(schema.getField(i).uid))
                    columnsToDrop.add(i);
            }
            
            if (!columnsToDrop.isEmpty()) return true;
            
            return false;
        }
        
        @SuppressWarnings("unchecked")
        private void addForeach(LogicalRelationalOperator op) throws IOException {
            Set<Long> outputUids = (Set<Long>)op.getAnnotation(ColumnPruneHelper.OUTPUTUIDS);
            LogicalSchema schema = op.getSchema();
            Set<Integer> columnsToDrop = new HashSet<Integer>();
            
            for (int i=0;i<schema.size();i++) {
                if (!outputUids.contains(schema.getField(i).uid))
                    columnsToDrop.add(i);
            }
            
            if (!columnsToDrop.isEmpty()) {
                LOForEach foreach = Util.addForEachAfter((LogicalPlan)op.getPlan(), op, columnsToDrop);
                op.annotate(STATUS, STATUSADDED);
                foreach.annotate(STATUS, STATUSNEWFOREACH);
            }
        }
    }          
}