// Exercises one invitation on the private channel granted by the fork fixture.
#include <stdint.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

#include <string>
#include <utility>
#include <vector>

#include "base/at_exit.h"
#include "base/check.h"
#include "base/check_op.h"
#include "base/command_line.h"
#include "base/feature_list.h"
#include "base/files/scoped_file.h"
#include "base/message_loop/message_pump_type.h"
#include "base/pickle.h"
#include "base/rand_util.h"
#include "base/strings/string_number_conversions.h"
#include "base/threading/thread.h"
#include "mojo/core/embedder/configuration.h"
#include "mojo/core/embedder/embedder.h"
#include "mojo/core/embedder/scoped_ipc_support.h"
#include "mojo/public/cpp/platform/platform_channel_endpoint.h"
#include "mojo/public/cpp/platform/platform_handle.h"
#include "mojo/public/cpp/system/invitation.h"
#include "mojo/public/cpp/system/message_pipe.h"
#include "mojo/public/cpp/system/wait.h"

namespace {
constexpr uint64_t kAttachment = 0;
constexpr size_t kNonceSize = 16;

void Send(mojo::MessagePipeHandle pipe, const std::vector<uint8_t>& bytes) {
  CHECK_EQ(mojo::WriteMessageRaw(pipe, bytes.data(), bytes.size(), nullptr, 0,
                                 MOJO_WRITE_MESSAGE_FLAG_NONE), MOJO_RESULT_OK);
}

std::vector<uint8_t> Receive(mojo::MessagePipeHandle pipe, size_t size) {
  CHECK_EQ(mojo::Wait(pipe, MOJO_HANDLE_SIGNAL_READABLE), MOJO_RESULT_OK);
  std::vector<uint8_t> bytes;
  CHECK_EQ(mojo::ReadMessageRaw(pipe, &bytes, nullptr, MOJO_READ_MESSAGE_FLAG_NONE),
           MOJO_RESULT_OK);
  CHECK_EQ(bytes.size(), size);
  return bytes;
}

void Exchange(mojo::MessagePipeHandle pipe, bool broker) {
  const auto mine = base::RandBytesAsVector(kNonceSize);
  if (broker) Send(pipe, mine);
  const auto theirs = Receive(pipe, kNonceSize);
  if (!broker) Send(pipe, mine);
  Send(pipe, theirs);
  CHECK(Receive(pipe, kNonceSize) == mine);
  printf("mojo_%s_pid=%d sent=%s received=%s echo=ok\n",
         broker ? "broker" : "child", getpid(), base::HexEncode(mine).c_str(),
         base::HexEncode(theirs).c_str());
}
}  // namespace

int main(int argc, char** argv) {
  // broker ENDPOINT PID CONTROL, or child ENDPOINT
  setvbuf(stdout, nullptr, _IONBF, 0);
  alarm(10);
  base::AtExitManager at_exit;
  base::CommandLine::Init(argc, argv);
  CHECK(argc == 3 || argc == 5);
  const bool broker = std::string(argv[1]) == "broker";
  CHECK(broker ? argc == 5 : argc == 3 && std::string(argv[1]) == "child");
  int fd, child_pid = -1, control = -1;
  CHECK(base::StringToInt(argv[2], &fd) && fd > 2);
  if (broker) {
    CHECK(base::StringToInt(argv[3], &child_pid) && child_pid > 1);
    CHECK(base::StringToInt(argv[4], &control) && control > 2 && control != fd);
  }
  CHECK(base::FeatureList::InitInstance("", ""));
  mojo::core::InitFeatures();
  mojo::core::Configuration config;
  config.is_broker_process = broker;
  config.direct_unsafe_shared_memory_allocation = !broker;
  mojo::core::Init(config);
  if (!broker) mojo::core::InstallMojoIpczBaseSharedMemoryHooks();
  base::Thread io("probe-mojo-io");
  CHECK(io.StartWithOptions(base::Thread::Options(base::MessagePumpType::IO, 0)));
  mojo::core::ScopedIPCSupport support(io.task_runner(),
      mojo::core::ScopedIPCSupport::ShutdownPolicy::CLEAN);
  mojo::PlatformChannelEndpoint endpoint{mojo::PlatformHandle{base::ScopedFD(fd)}};
  mojo::ScopedMessagePipeHandle pipe;
  if (broker) {
    mojo::OutgoingInvitation invitation;
    pipe = invitation.AttachMessagePipe(kAttachment);
    CHECK(pipe.is_valid());
    mojo::OutgoingInvitation::Send(std::move(invitation), child_pid, std::move(endpoint));
    puts("mojo_invitation=sent attachment=0");
  } else {
    auto invitation = mojo::IncomingInvitation::Accept(std::move(endpoint));
    CHECK(invitation.is_valid());
    pipe = invitation.ExtractMessagePipe(kAttachment);
    CHECK(pipe.is_valid());
    puts("mojo_invitation=accepted attachment=0");
  }
  Exchange(pipe.get(), broker);
  if (broker) {
    // Wait for the child to verify its echo and close its pipe before Reap.
    CHECK_EQ(mojo::Wait(pipe.get(), MOJO_HANDLE_SIGNAL_PEER_CLOSED), MOJO_RESULT_OK);
    base::Pickle reap;
    reap.WriteInt(1);
    reap.WriteInt(child_pid);
    CHECK_EQ(send(control, reap.data(), reap.size(), MSG_NOSIGNAL),
             static_cast<ssize_t>(reap.size()));
    printf("mojo_reap_requested=%d\n", child_pid);
  }
  return 0;
}
