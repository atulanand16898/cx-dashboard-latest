import React from 'react'
import './ServerRoomBackdrop.css'

const RACKS = Array.from({ length: 6 }, (_, index) => index)

export default function ServerRoomBackdrop({ className = '', children }) {
  return (
    <div className={`server-room-backdrop ${className}`.trim()}>
      <div className="server-room-backdrop__ambient" />
      <div className="server-room-backdrop__ceiling" />
      <div className="server-room-backdrop__wall server-room-backdrop__wall--left">
        {RACKS.map((rack) => (
          <div key={`left-${rack}`} className="server-room-backdrop__rack" />
        ))}
      </div>
      <div className="server-room-backdrop__wall server-room-backdrop__wall--right">
        {RACKS.map((rack) => (
          <div key={`right-${rack}`} className="server-room-backdrop__rack" />
        ))}
      </div>
      <div className="server-room-backdrop__aisle-glow" />
      <div className="server-room-backdrop__floor" />
      {children}
    </div>
  )
}
