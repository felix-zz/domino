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

package ict.wde.domino.client;

import ict.wde.domino.common.DominoIface;
import ict.wde.domino.common.HTableWrapper;
import ict.wde.domino.common.InvalidRowStatusException;
import ict.wde.domino.common.MVCC;
import ict.wde.domino.common.TransactionOutOfDateException;
import ict.wde.domino.common.writable.DResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;

/**
 * A ResultScanner defined by Domino which implements the HBase ResultScanner
 * interface. Some inner operations are taken to ensure the ACID features.
 * 
 * @author Zhen Zhao, ICT, CAS
 * 
 */
public class DResultScanner implements ResultScanner, HTableWrapper {

  final ResultScanner scanner;
  final long startId;
  final HTableInterface metaTable;
  final HTableInterface table;
  final Transaction trx;

  protected DResultScanner(ResultScanner scanner, long startId,
      HTableInterface metaTable, HTableInterface table, Transaction trx) {
    this.scanner = scanner;
    this.startId = startId;
    this.metaTable = metaTable;
    this.table = table;
    this.trx = trx;
  }

  @Override
  public Iterator<Result> iterator() {
    return new InnerIt(scanner.iterator());
  }

  @Override
  public Result next() throws IOException {
    trx.checkIfReadyToContinue();
    try {
      while (true) {
        Result result = scanner.next();
        if (result == null || result.isEmpty()) return result;
        result = MVCC.handleResult(this, metaTable, result, startId, null);
        if (result.isEmpty()) continue;
        return result;
      }
    }
    catch (TransactionOutOfDateException e) {
      trx.disable();
      throw new IOException(e);
    }
    catch (InvalidRowStatusException e) {
      trx.disable();
      throw new IOException(e);
    }
    catch (IOException e) {
      trx.disable();
      throw e;
    }
  }

  @Override
  public Result[] next(int nbRows) throws IOException {
    trx.checkIfReadyToContinue();
    try {
      Result[] result = scanner.next(nbRows);
      if (result == null || result.length == 0) return result;
      List<Result> temp = new ArrayList<Result>(result.length);
      int targLength = result.length;
      while (result != null && result.length > 0) {
        for (int i = 0; i < result.length; ++i) {
          Result r = MVCC.handleResult(this, metaTable, result[i], startId,
              null);
          if (r == null || r.isEmpty()) {
            continue;
          }
          temp.add(r);
        }
        if (temp.size() >= targLength) {
          break;
        }
        result = scanner.next(targLength - temp.size());
      }
      return temp.toArray(new Result[temp.size()]);
    }
    catch (TransactionOutOfDateException e) {
      trx.disable();
      throw new IOException(e);
    }
    catch (InvalidRowStatusException e) {
      trx.disable();
      throw new IOException(e);
    }
    catch (IOException e) {
      trx.disable();
      throw e;
    }
  }

  @Override
  public void close() {
    scanner.close();
  }

  private class InnerIt implements Iterator<Result> {

    final Iterator<Result> it;
    Result next = null;

    InnerIt(Iterator<Result> it) {
      this.it = it;
    }

    @Override
    public boolean hasNext() {
      if (next != null && !next.isEmpty()) return true;
      while (it.hasNext()) {
        try {
          trx.checkIfReadyToContinue();
          next = MVCC.handleResult(DResultScanner.this, metaTable, it.next(),
              startId, null);
          if (next == null || next.isEmpty()) continue;
          break;
        }
        catch (TransactionOutOfDateException e) {
          trx.disable();
          throw new RuntimeException(e);
        }
        catch (InvalidRowStatusException e) {
          trx.disable();
          throw new RuntimeException(e);
        }
        catch (IOException e) {
          trx.disable();
          throw new RuntimeException(e);
        }
      }
      return next != null && !next.isEmpty();
    }

    @Override
    public Result next() {
      if (!hasNext()) return null;
      Result ret = next;
      next = null;
      return ret;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

  @Override
  public Result get(Get get) throws IOException {
    DResult res = table.coprocessorProxy(DominoIface.class, get.getRow()).get(
        get, startId);
    if (res.getErrMsg() != null) {
      throw new IOException(res.getErrMsg());
    }
    return res.getResult();
  }

  @Override
  public Result get(Get get, Integer lockId) throws IOException {
    // Used when MVCC is checking if the transaction is expired.
    return table.get(get);
  }

  @Override
  public void rollbackRow(byte[] row, long startId, Integer lockId)
      throws IOException {
    table.coprocessorProxy(DominoIface.class, row).rollbackRow(row, startId);
  }

  @Override
  public void commitRow(byte[] row, long startId, long commitId,
      boolean isDelete, Integer lockId) throws IOException {
    table.coprocessorProxy(DominoIface.class, row).commitRow(row, startId,
        commitId, isDelete);
  }

  @Override
  public byte[] getName() {
    return table.getTableName();
  }

}
