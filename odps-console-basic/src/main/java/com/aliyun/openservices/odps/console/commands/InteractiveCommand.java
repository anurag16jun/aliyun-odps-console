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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.openservices.odps.console.commands;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aliyun.odps.OdpsException;
import com.aliyun.odps.utils.StringUtils;
import com.aliyun.openservices.odps.console.ExecutionContext;
import com.aliyun.openservices.odps.console.ODPSConsoleException;
import com.aliyun.openservices.odps.console.constants.ODPSConsoleConstants;
import com.aliyun.openservices.odps.console.utils.CommandParserUtils;
import com.aliyun.openservices.odps.console.utils.ODPSConsoleUtils;

import jline.console.ConsoleReader;
import jline.console.UserInterruptException;
import jline.console.history.FileHistory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * 处理交互模式
 */
public class InteractiveCommand extends AbstractCommand {

  public static boolean quit = false;

  public static boolean isInteractiveMode = false;

  // 在window下和linux下输入方式不一样
  Scanner scanner = null;
  ConsoleReader consoleReader = null;

  @SuppressWarnings("restriction")
  public void run() throws OdpsException, ODPSConsoleException {

    // 设定交互模式
    isInteractiveMode = true;
    // 交互模式没有step的说法
    getContext().setStep(0);

    // 欢迎版本信息
    getWriter().writeError(ODPSConsoleConstants.ALIYUN_ODPS_UTILITIES_VERSION);

    // window下用sconner来读取command，其它的都用jline来处理,因为jline在window下处理不好输入。
    try {

      if (ODPSConsoleUtils.isWindows()) {
        scanner = new Scanner(System.in);
      } else {
        consoleReader = new ConsoleReader();
        consoleReader.setExpandEvents(false); // disable jline event
        consoleReader.setHandleUserInterrupt(true);

        // reset terminal after Ctrl+Z & fg
        Signal.handle(new Signal("CONT"), new SignalHandler() {
          public void handle(final Signal sig) {
            try {
              consoleReader.getTerminal().reset();
            } catch (Exception e) {
            }
          }
        });

        final Thread currentThread = Thread.currentThread();

        Signal.handle(new Signal("INT"), new SignalHandler() {
          public void handle(Signal sig) {
            if (currentThread.isInterrupted()) {
              System.exit(128 + sig.getNumber());
            } else {
              currentThread.interrupt();
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
                //do nothing
              }
              if (currentThread.isInterrupted()) {
                System.err.println("Press Ctrl-C again to exit ODPS console");
              }
            }
          }
        });

        try {
          final String HISTORYFILE = ".odps_history";
          String historyFile = System.getProperty("user.home") + File.separator + HISTORYFILE;
          consoleReader.setHistory(new FileHistory(new File(historyFile)));
          Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
              try {
                ((FileHistory) consoleReader.getHistory()).flush();
              } catch (IOException ex) {
              }
            }
          });
        } catch (IOException e) {
          // ignore file history failure
        }
      }

      // 判断accessid是否为空
      if (getContext().getAccessId() == null) {
        System.out.print(ODPSConsoleConstants.ODPS_LOGIN);

        String username = "";
        String password = "";
        if (ODPSConsoleUtils.isWindows()) {
          username = scanner.nextLine();
          System.out.print("Password:");
          password = scanner.nextLine();
        } else {
          username = consoleReader.readLine();
          password = consoleReader.readLine("Password:", new Character('*'));
        }

        LoginCommand la = new LoginCommand(username, password, "-u-p", getContext());
        la.run();
      }
    } catch (IOException e) {
    }

    String inputStr = "";
    String endPoint = getContext().getEndpoint();
    String ip = "";
    if (endPoint.indexOf("//") > 0) {
      ip = "@" + endPoint.substring(endPoint.indexOf("//") + 2);
    } else {
      System.err.println("Failed :please set right endpoint.");
      return;
    }

    // 初始的交互模式前缀
    String prefix = "odps@ " + getContext().getProjectName();

    StringBuilder stringBuf = new StringBuilder();

    FutureTask<Object> asyncUseProject = null;
    if (getContext().getProjectName() != null && !getContext().getProjectName().isEmpty()) {

      asyncUseProject = new FutureTask<Object>(new Callable<Object>() {
        @Override
        public Object call() {
          String projectName = getContext().getProjectName();
          try {
            String commandText = "use project " + projectName;
            UseProjectCommand useProjectCommand = new UseProjectCommand(projectName, commandText,
                                                                        getContext());
            useProjectCommand.run();
            return useProjectCommand;
          } catch (OdpsException ex) {
            return "Accessing project '" + projectName + "' failed: " + ex.getMessage();
          } catch (ODPSConsoleException e) {
            return "Accessing project '" + projectName + "' failed: " + e.getMessage();
          }
        }
      });
      new Thread(asyncUseProject).start();
    }

    // q;会退出，还有一种情况，ctrl+d时inputStr返回null
    while (inputStr != null) {

      if (!StringUtils.isNullOrEmpty(inputStr) && !inputStr.trim().startsWith("--")) {

        // 和declient一样，在交互模式下，忽略注释行
        stringBuf.append(inputStr + "\n");
        // 只有;结束，才执行
        if (inputStr.trim().endsWith(";")) {
          // 如果输入不为空，则解析命令并执行
          try {
            String commandStr = stringBuf.toString();
            // 清空
            stringBuf = new StringBuilder();

            // drop command, need confirm
            if (isConfirm(commandStr)) {
              AbstractCommand command = CommandParserUtils.parseCommand(commandStr, this.getContext());
              if (asyncUseProject != null) {
                Object result = asyncUseProject.get();
                asyncUseProject = null;

                if (result != null) {

                  if (result instanceof String) {
                    // XXX ignore async use project failure if this command is use
                    // project command
                    String msg = (String) result;
                    if (msg != null && !(command instanceof UseProjectCommand)) {
                      throw new ODPSConsoleException(msg);
                    }
                  } else if (result instanceof UseProjectCommand) {
                    String msg = ((UseProjectCommand) result).getMsg();
                    if (!StringUtils.isNullOrEmpty(msg)) {
                      System.err.println(msg);
                    }
                  }
                }
              }
              command.run();
            }

            if (quit) {
              break;
            }
          } catch (InterruptedException e) {
            // isComfirn may throw too
            inputStr = "";
            continue;
          } catch (UserInterruptException e) {
            // isComfirn may throw too
            inputStr = "";
            continue;
          } catch (Exception e) {
            getWriter().writeError(ODPSConsoleConstants.FAILED_MESSAGE + e.getMessage());
          }
          //
          prefix = "odps@ " + getContext().getProjectName();

        } else {

          // 把所有字符都换成空格，支持多行命令输入，";"为语句结束的标记，标识符需要对齐
          prefix = prefix.replaceAll(".", " ");
        }
      }

      // 显示输入行的前缀, 使用System.out.print，因为提示符的原因
      if (getContext().getProjectName() != null) {
        if (ODPSConsoleUtils.isWindows()) {
          System.out.print(prefix + ODPSConsoleConstants.IDENTIFIER);
          inputStr = scanner.nextLine();
        } else {
          inputStr = readLine(prefix + ODPSConsoleConstants.IDENTIFIER);
        }
      }
    }

    // 退出后，换一行，显示格式更好看一些
    System.err.println("");
  }

  /**
   * if return is null, don't need confirm or suggest confirm
   */
  protected String getConfirmInfomation(String commandText) {

    String upCommandText = commandText.trim().toUpperCase();

    if (upCommandText.startsWith("DROP") // resource, function, role
        || upCommandText.startsWith("DELETE") // resource, function
        || upCommandText.startsWith("REMOVE") // resource, user
        || isDropPartitionCmd(upCommandText)) {
      // delete \n
      return "Confirm to \"" + commandText.substring(0, commandText.length() - 1) + "\" (yes/no)? ";
    }

    if (upCommandText.matches("PUT\\s+POLICY\\s+.*")
        || (upCommandText.matches("SET\\s+PROJECTPROTECTION.*") && upCommandText
                                                                       .indexOf("EXCEPTION ") > 0)) {
      return "will overwrite the old policy content  (yes/no)? ";
    }

    return null;
  }

  private boolean isConfirm(String commandText) throws IOException {

    String confirmText = getConfirmInfomation(commandText);
    if (confirmText == null) {
      // don't confirm
      return true;
    }

    String inputStr = "";
    while (true) {
      if (ODPSConsoleUtils.isWindows()) {
        System.out.print(confirmText);
        inputStr = scanner.nextLine();
      } else {
        inputStr = readLine(confirmText);
      }

      if (inputStr == null) {
        return false;
      }

      if (inputStr.trim().toUpperCase().equals("N") || inputStr.trim().toUpperCase().equals("NO")) {
        return false;
      } else if (inputStr.trim().toUpperCase().equals("Y")
                 || inputStr.trim().toUpperCase().equals("YES")) {
        return true;
      }
    }

  }

  private String readLine(String prompt) {
    //clear the interrupted flag
    Thread.currentThread().interrupted();
    try {
      String input = consoleReader.readLine(prompt);
      return input;
    } catch (UserInterruptException e) {
      if (StringUtils.isNullOrEmpty(e.getPartialLine())) {
        return null;
      } else {
        return "";
      }
    } catch (IOException e) {
      return "";
    }
  }

  public InteractiveCommand(String commandText, ExecutionContext context) {
    super(commandText, context);
  }

  /**
   * 通过传递的参数，解析出对应的command
   **/
  public static InteractiveCommand parse(List<String> paraList, ExecutionContext sessionContext) {
    InteractiveCommand ic = null;
    if (paraList.size() == 0) {
      return new InteractiveCommand("", sessionContext);
    }

    if (paraList.contains("-e") && paraList.indexOf("-e") + 1 == paraList.size()) {
      // 如果没有command，让此命令走交互模式
      paraList.remove(paraList.indexOf("-e"));

      return new InteractiveCommand("", sessionContext);
    }

    return ic;
  }

  private static Pattern IS_DROP_PARTITION = Pattern.compile(
      "\\s*ALTER\\s+TABLE\\s+[\\w\\.]+\\s+DROP.+", Pattern.CASE_INSENSITIVE);

  static boolean isDropPartitionCmd(String cmd) {
    boolean r = false;
    if (cmd != null) {
      Matcher m = IS_DROP_PARTITION.matcher(cmd);
      r = m.matches();
    }
    return r;
  }
}