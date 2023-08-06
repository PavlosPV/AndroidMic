use anyhow::{self};
use std::{net::UdpSocket, sync::{Mutex, Arc}};
use cpal::{
    traits::{DeviceTrait, HostTrait, StreamTrait},
    FromSample, Sample,
};




fn main() -> anyhow::Result<()> {
    // Replace this with the port you want to bind to.
    let bind_port = 55555;

    // Create a UDP socket and bind it to the specified port
    let socket = UdpSocket::bind(("0.0.0.0", bind_port)).expect("Failed to bind to socket");


    println!("Waiting for data...");
    let host = cpal::default_host();
    let device = host.default_output_device().unwrap();
    let config: cpal::StreamConfig = device.default_output_config().unwrap().into();
    println!("Default output config: {:?}", config);

    let sample_rate = config.sample_rate.0 as f32;
    let channels = config.channels as usize;

    // Buffer to store received data
    let mut buf = [0u8; 1024];
    let shared_buf = Arc::new(Mutex::new(buf));

 

    let err_fn = |err| eprintln!("an error occurred on stream: {}", err);

    let audio_buf = shared_buf.clone();
    let stream = device.build_output_stream(
        &config,
        move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
            let inner_buf = audio_buf.lock().unwrap();
            let mut i = 0;

            // a frame has 480 samples
            for frame in data.chunks_mut(channels) {
                if i >= 1024 {
                    break;
                }
                let value = i16::from_sample(inner_buf[i]); 
                i += 1;

                // a sample has two cases (probably left/right)
                for sample in frame.iter_mut() {
                    *sample = value;
                }
            }
        },
        err_fn,
        None,
    )?;
    stream.play()?;

    loop {
        let mut tmp_buf = [0u8; 1024];
        // Receive data into the buffer
        match socket.recv_from(&mut tmp_buf) {
            Ok((size, src_addr)) => {
                
                let inner_buf = shared_buf.lock().unwrap();
                let inner_buf = &buf[..size];
                let src_addr = src_addr.to_string();
                println!("Received {} bytes from {}", size, src_addr);
            }
            Err(e) => {
                eprintln!("Error while receiving data: {:?}", e);
                break;
            }
        }
    }
    Ok(())
}






fn write_data<T>(output: &mut [T], channels: usize, next_sample: &mut dyn FnMut() -> f32)
where
    T: Sample + FromSample<f32>,
{
    for frame in output.chunks_mut(channels) {
        let value: T = T::from_sample(next_sample());
        for sample in frame.iter_mut() {
            *sample = value;
        }
    }
}