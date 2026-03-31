using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;
using PhonePadService;

class Program
{
    static readonly Guid ServiceUuid = Guid.Parse("12345678-1234-1234-1234-1234567890ab");
    const string ServiceName = "PhonePad Mouse";

    static async Task Main(string[] args)
    {
        Console.WriteLine("PhonePad Server - Classic Bluetooth RFCOMM");
        Console.WriteLine("==========================================");

        StartBluetoothWatcher();

        try
        {
            // Create the RFCOMM service provider
            var provider = await RfcommServiceProvider.CreateAsync(
                RfcommServiceId.FromUuid(ServiceUuid));

            // Set SDP attributes so Android can find us
            var writer = new DataWriter();
            writer.WriteByte(0x35); writer.WriteByte(0x27);     // SDP sequence
            writer.WriteByte(0x1c);                              // UUID element, 16 bytes
            // Write UUID bytes
            byte[] uuidBytes = ServiceUuid.ToByteArray();
            // SDP wants big-endian UUID
            byte[] uuidBE = new byte[] {
                0x12,0x34,0x56,0x78, 0x12,0x34, 0x12,0x34,
                0x12,0x34, 0x12,0x34,0x56,0x78,0x90,0xab
            };
            foreach (var b in uuidBE) writer.WriteByte(b);
            writer.WriteByte(0x08); writer.WriteByte(0x00);      // uint8 element
            provider.SdpRawAttributes.Add(0x0100, writer.DetachBuffer());

            // Open a socket listener
            var socketListener = new StreamSocketListener();
            socketListener.ConnectionReceived += SocketListener_ConnectionReceived;

            // Bind
            await socketListener.BindServiceNameAsync(
                provider.ServiceId.AsString(),
                SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);

            // Advertise
            provider.StartAdvertising(socketListener);

            Console.WriteLine($"\nRFCOMM Server running!");
            Console.WriteLine($"Service UUID: {ServiceUuid}");
            Console.WriteLine("Waiting for Android connection...");

            // In WinExe mode, we keep the task alive indefinitely.
            await Task.Delay(-1);
            
            provider.StopAdvertising();
            socketListener.Dispose();
        }
        catch (Exception ex)
        {
            // Logging would go to a file here normally, for now we just fail silently or exit
        }
    }

    private static void SocketListener_ConnectionReceived(
        StreamSocketListener sender,
        StreamSocketListenerConnectionReceivedEventArgs args)
    {
        Console.WriteLine($"[Connected] Android device connected!");
        _ = HandleConnectionAsync(args.Socket);
    }

    private static async Task HandleConnectionAsync(StreamSocket socket)
    {
        var reader = new DataReader(socket.InputStream);
        reader.InputStreamOptions = InputStreamOptions.Partial;
        bool firstPacket = true;

        try
        {
            while (true)
            {
                uint loaded = await reader.LoadAsync(16);
                if (loaded == 0) break;

                if (firstPacket)
                {
                    firstPacket = false;
                    Console.WriteLine("[Data] Receiving mouse data from Android!");
                }

                byte type = reader.ReadByte();
                switch (type)
                {
                    case 1: // Move
                        if (loaded >= 3)
                        {
                            sbyte dx = (sbyte)reader.ReadByte();
                            sbyte dy = (sbyte)reader.ReadByte();
                            MouseInjector.Move(dx, dy);
                        }
                        break;
                    case 2: MouseInjector.SendLeftClick(); break;
                    case 3: MouseInjector.SendRightClick(); break;
                    case 4: MouseInjector.SendLeftDown(); break;
                    case 5: MouseInjector.SendLeftUp(); break;
                    case 6:
                        if (loaded >= 2)
                            MouseInjector.Scroll((sbyte)reader.ReadByte());
                        break;
                    default:
                        // Drain remaining bytes
                        if (loaded > 1)
                        {
                            var drain = new byte[loaded - 1];
                            reader.ReadBytes(drain);
                        }
                        break;
                }
                // Drain leftover bytes for this packet
                uint remaining = reader.UnconsumedBufferLength;
                if (remaining > 0)
                {
                    var leftover = new byte[remaining];
                    reader.ReadBytes(leftover);
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[Disconnected] {ex.Message}");
        }

        Console.WriteLine("[Disconnected] Android device disconnected.");
        socket.Dispose();
    }

    private static void StartBluetoothWatcher()
    {
        string selector = BluetoothDevice.GetDeviceSelectorFromConnectionStatus(BluetoothConnectionStatus.Connected);
        var watcher = DeviceInformation.CreateWatcher(selector);
        watcher.Added += (s, args) => Console.WriteLine($"[BT] Device connected: {args.Name}");
        watcher.Removed += (s, args) => Console.WriteLine($"[BT] Device disconnected: {args.Id}");
        watcher.Start();
        Console.WriteLine("[BT] Monitoring device connections...");
    }
}
