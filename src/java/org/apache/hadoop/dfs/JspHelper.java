/**
 * Copyright 2005 The Apache Software Foundation
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

package org.apache.hadoop.dfs;

import javax.servlet.*;
import javax.servlet.jsp.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.net.*;
import org.apache.hadoop.dfs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.conf.*;

public class JspHelper {
    static FSNamesystem fsn = null;
    static InetSocketAddress nameNodeAddr;
    static Configuration conf = new Configuration();

    static int defaultChunkSizeToView = 
                        conf.getInt("dfs.default.chunk.view.size",2 * 1024);
    static Random rand = new Random();

    public JspHelper() {
      if (DataNode.getDataNode() != null) {
        nameNodeAddr = DataNode.getDataNode().getNameNodeAddr();
      }
      else {
        fsn = FSNamesystem.getFSNamesystem();
        nameNodeAddr = new InetSocketAddress(fsn.getDFSNameNodeMachine(),
                  fsn.getDFSNameNodePort()); 
      }      
    }
    public DatanodeInfo bestNode(LocatedBlock blk) throws IOException {
      TreeSet deadNodes = new TreeSet();
      DatanodeInfo chosenNode = null;
      int failures = 0;
      Socket s = null;
      DatanodeInfo [] nodes = blk.getLocations();
      if (nodes == null || nodes.length == 0) {
        throw new IOException("No nodes contain this block");
      }
      while (s == null) {
        if (chosenNode == null) {
          do {
            chosenNode = nodes[rand.nextInt(nodes.length)];
          } while (deadNodes.contains(chosenNode));
        }
        int index = rand.nextInt(nodes.length);
        chosenNode = nodes[index];

        //just ping to check whether the node is alive
        InetSocketAddress targetAddr = DataNode.createSocketAddr(chosenNode.getHost() + ":" + chosenNode.getInfoPort());
        
        try {
          s = new Socket();
          s.connect(targetAddr, FSConstants.READ_TIMEOUT);
          s.setSoTimeout(FSConstants.READ_TIMEOUT);
        } catch (IOException e) {
          deadNodes.add(chosenNode);
          s.close();
          s = null;
          failures++;
        }
        if (failures == nodes.length)
          throw new IOException("Could not reach the block containing the data. Please try again");
        
      }
      s.close();
      return chosenNode;
    }
    public void streamBlockInAscii(InetSocketAddress addr, long blockId, long blockSize, 
            long offsetIntoBlock, long chunkSizeToView, JspWriter out) 
      throws IOException {
      if (chunkSizeToView == 0) return;
      Socket s = new Socket();
      s.connect(addr, FSConstants.READ_TIMEOUT);
      s.setSoTimeout(FSConstants.READ_TIMEOUT);
      //
      // Xmit header info to datanode
      //
      DataOutputStream os = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
      os.write(FSConstants.OP_READSKIP_BLOCK);
      new Block(blockId, blockSize).write(os);
      os.writeLong(offsetIntoBlock);
      os.flush();

      //
      // Get bytes in block, set streams
      //
      DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
      long curBlockSize = in.readLong();
      long amtSkipped = in.readLong();
      if (curBlockSize != blockSize) {
        throw new IOException("Recorded block size is " + blockSize + ", but datanode reports size of " + curBlockSize);
      }
      if (amtSkipped != offsetIntoBlock) {
        throw new IOException("Asked for offset of " + offsetIntoBlock + ", but only received offset of " + amtSkipped);
      }
      
      long amtToRead = chunkSizeToView;
      if (amtToRead + offsetIntoBlock > blockSize)
        amtToRead = blockSize - offsetIntoBlock;
      byte[] buf = new byte[(int)amtToRead];
      int readOffset = 0;
      int retries = 2;
      while (true) {
        int numRead;
        try {
          numRead = in.read(buf, readOffset, (int)amtToRead);
        }
        catch (IOException e) {
          retries--;
          if (retries == 0)
            throw new IOException("Could not read data from datanode");
          continue;
        }
        amtToRead -= numRead;
        readOffset += numRead;
        if (amtToRead == 0)
          break;
      }
      s.close();
      in.close();
      out.print(new String(buf));
    }
    public void DFSNodesStatus(Vector live, Vector dead) {
      if (fsn == null) return;
      TreeMap nodesSortedByName = new TreeMap();
      fsn.DFSNodesStatus(live, dead);
      for (int num = 0; num < live.size(); num++) {
        DatanodeInfo d = (DatanodeInfo)live.elementAt(num);
        nodesSortedByName.put(d.getName(), d);
      }
      live.clear();
      live.addAll(nodesSortedByName.values());
    }
    public void addTableHeader(JspWriter out) throws IOException {
      out.print("<table border=\"1\""+
                " cellpadding=\"2\" cellspacing=\"2\">");
      out.print("<tbody>");
    }
    public void addTableRow(JspWriter out, String[] columns) throws IOException {
      out.print("<tr>");
      for (int i = 0; i < columns.length; i++) {
        out.print("<td style=\"vertical-align: top;\"><B>"+columns[i]+"</B><br></td>");
      }
      out.print("</tr>");
    }
    public void addTableFooter(JspWriter out) throws IOException {
      out.print("</tbody></table>");
    }

}
