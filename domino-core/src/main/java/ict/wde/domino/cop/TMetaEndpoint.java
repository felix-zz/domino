/**
 *  Copyright 2014 ZhenZhao and Tieying Zhang. 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ict.wde.domino.cop;

import ict.wde.domino.common.DominoConst;
import ict.wde.domino.common.TMetaIface;
import ict.wde.domino.common.Version;
import ict.wde.domino.id.DominoIdIface;
import ict.wde.domino.id.DominoIdService;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.ipc.ProtocolSignature;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction Metadata Endpoint Implementation.
 * 
 * @author Zhen Zhao, ICT, CAS
 * 
 */
public class TMetaEndpoint implements TMetaIface {

  static final Logger LOG = LoggerFactory.getLogger(TMetaEndpoint.class);

  private HRegion region;
  private HTableInterface metaTable = null;
  private DominoIdIface tidClient;

  private Configuration conf = null;

  @Override
  public void start(CoprocessorEnvironment env) throws IOException {
    LOG.info("-------------TrxMetaEndpoint starting, version:{} ------------",
        Version.VERSION);
    // this.env = env;
    conf = env.getConfiguration();
    tidClient = DominoIdService.getClient(conf);
    this.region = ((RegionCoprocessorEnvironment) env).getRegion();
  }

  @Override
  public void stop(CoprocessorEnvironment env) throws IOException {
    if (metaTable != null) metaTable.close();
    // this.env = null;
    // this.conf = null;
    this.region = null;
  }

  @Override
  public ProtocolSignature getProtocolSignature(String arg0, long arg1, int arg2)
      throws IOException {
    return new ProtocolSignature(Version.VERSION, null);
  }

  @Override
  public long getProtocolVersion(String arg0, long arg1) throws IOException {
    return Version.VERSION;
  }

  @SuppressWarnings("deprecation")
  @Override
  public long commitTransaction(byte[] startId) throws IOException {
    Integer lockId = region.getLock(null, startId, true);
    try {
      long commitId = tidClient.getId();
      long startIdLong = DominoConst.getTidFromTMetaKey(startId);
      Get get = new Get(startId);
      Result r = region.get(get, lockId);
      if (DominoConst.TRX_ACTIVE != DominoConst.transactionStatus(r)) {
        return DominoConst.ERR_TRX_ABORTED;
      }
      Put put = new Put(startId);
      put.add(DominoConst.TRANSACTION_META_FAMILY,
          DominoConst.TRANSACTION_STATUS, startIdLong,
          DominoConst.TRX_COMMITTED_B);
      put.add(DominoConst.TRANSACTION_META_FAMILY,
          DominoConst.TRANSACTION_COMMIT_ID, startIdLong,
          Bytes.toBytes(commitId));
      region.put(put, lockId, true);
      return commitId;
    }
    finally {
      region.releaseRowLock(lockId);
    }
  }

  @Override
  public void abortTransaction(byte[] startId) throws IOException {
    Integer lockId = region.getLock(null, startId, true);
    try {
      abort(startId, lockId);
    }
    finally {
      region.releaseRowLock(lockId);
    }
  }

  @SuppressWarnings("deprecation")
  private void abort(byte[] startId, Integer lockId) throws IOException {
    Put put = new Put(startId);
    long startIdLong = DominoConst.getTidFromTMetaKey(startId);
    put.add(DominoConst.TRANSACTION_META_FAMILY,
        DominoConst.TRANSACTION_STATUS, startIdLong, DominoConst.TRX_ABORTED_B);
    region.put(put, lockId, true);
  }

  @SuppressWarnings("deprecation")
  @Override
  public Result getTransactionStatus(long transactionId) throws IOException {
    byte[] row = DominoConst.long2TranscationRowKey(transactionId);
    Integer lockId = region.getLock(null, row, true);
    try {
      Get get = new Get(row);
      Result r = region.get(get, lockId);
      if (System.currentTimeMillis() - DominoConst.getLastTouched(r) > DominoConst.TRX_EXPIRED) {
        // If it's too long since the client last updated the transaction
        // timestamp, the client may be no longer alive.
        // So we have to mark the transaction as aborted and let the caller
        // clear the row status.
        abort(row, lockId);
        return region.get(get, lockId);
      }
      else {
        return r;
      }
    }
    finally {
      region.releaseRowLock(lockId);
    }
  }

}
