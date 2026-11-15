// Calls a Fory gRPC endpoint published by GlassFish for a remote EJB view.
//
// The models and the codec come from the code the Fory compiler generates for
// greeter.fdl; only the method path is supplied here. The generated stub calls
// the canonical gRPC path, "/<package>.<Service>/<Method>", while GlassFish
// mounts its endpoints under a context path, so the path is passed in. A gRPC
// method name is just the HTTP/2 :path, so invoking it directly is enough.
//
// Usage:
//
//	fory-grpc-client -target host:port -path /glassfish-services/fory/<service>/SayHello -name Ada
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"

	greeter "compatibility/fory-grpc/generated/go/greeter"
)

func main() {
	target := flag.String("target", "localhost:8080", "host:port of the GlassFish HTTP listener")
	base := flag.String("base", "/glassfish-services/fory/compatibility.greeter.Greeter", "service path, without the method")
	name := flag.String("name", "Ada", "value to send")
	greeting := flag.String("greeting", "Hello ", "what sayHello is expected to put before the name")
	answer42 := flag.Int("answer", 42, "what answer() is expected to return")
	unimplemented := flag.Bool("unimplemented", false, "expect every call to be UNIMPLEMENTED (nothing is published)")
	timeout := flag.Duration("timeout", 10*time.Second, "call timeout")
	flag.Parse()

	var err error
	conn, err := grpc.NewClient(*target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		fail("dial %s: %v", *target, err)
	}
	defer conn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	// ForceCodecV2 puts "application/grpc+fory" on the wire, which is what the
	// GlassFish adapter answers to; the status comes back in the trailers.
	call := func(method string, request, response any) error {
		return conn.Invoke(ctx, *base+"/"+method, request, response, grpc.ForceCodecV2(greeter.CodecV2{}))
	}
	if *unimplemented {
		twice := new(greeter.TwiceResponse)
		err = call("Twice", &greeter.TwiceRequest{Value: 21}, twice)
		if status.Code(err) != codes.Unimplemented {
			fail("expected UNIMPLEMENTED for a bean that is not deployed, got %v", err)
		}
		fmt.Println("PASS undeployed bean: UNIMPLEMENTED")
		return
	}
	failures := 0
	check := func(name string, err error, ok bool, got any) {
		switch {
		case err != nil:
			fmt.Printf("FAIL %s: %v\n", name, err)
			failures++
		case !ok:
			fmt.Printf("FAIL %s: got %v\n", name, got)
			failures++
		default:
			fmt.Printf("PASS %s: %v\n", name, got)
		}
	}

	hello := new(greeter.SayHelloResponse)
	err = call("SayHello", &greeter.SayHelloRequest{Value: *name}, hello)
	check("sayHello", err, hello.Value == *greeting+*name, hello.Value)

	twice := new(greeter.TwiceResponse)
	err = call("Twice", &greeter.TwiceRequest{Value: 21}, twice)
	check("int twice(int)", err, twice.Value == 42, twice.Value)

	ping := new(greeter.PingResponse)
	err = call("Ping", &greeter.PingRequest{}, ping)
	check("void ping()", err, true, "ok")

	answer := new(greeter.AnswerResponse)
	err = call("Answer", &greeter.AnswerRequest{}, answer)
	check("int answer()", err, answer.Value == int32(*answer42), answer.Value)

	plusOne := new(greeter.PlusOneResponse)
	err = call("PlusOne", &greeter.PlusOneRequest{Value: 5000000000}, plusOne)
	check("long plusOne(long)", err, plusOne.Value == 5000000001, plusOne.Value)

	negate := new(greeter.NegateResponse)
	err = call("Negate", &greeter.NegateRequest{Value: true}, negate)
	check("boolean negate(boolean)", err, negate.Value == false, negate.Value)

	half := new(greeter.HalfResponse)
	err = call("Half", &greeter.HalfRequest{Value: 5.0}, half)
	check("double half(double)", err, half.Value == 2.5, half.Value)

	nextByte := new(greeter.NextByteResponse)
	err = call("NextByte", &greeter.NextByteRequest{Value: 41}, nextByte)
	check("byte nextByte(byte)", err, nextByte.Value == 42, nextByte.Value)

	nextShort := new(greeter.NextShortResponse)
	err = call("NextShort", &greeter.NextShortRequest{Value: 1000}, nextShort)
	check("short nextShort(short)", err, nextShort.Value == 1001, nextShort.Value)

	if failures > 0 {
		fail("%d call(s) failed", failures)
	}
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
