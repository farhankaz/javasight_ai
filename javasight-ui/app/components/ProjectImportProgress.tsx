'use client'

import { useState, useEffect } from 'react'
import { Spinner } from './ui/spinner'

interface ProjectImportProgressProps {
  importId: string
  onComplete: () => void
}

interface ImportStatus {
  type: 'connection' | 'status' | 'error'
  status: 'pending' | 'in_progress' | 'completed' | 'failed'
  message: string
  progress: number
  error?: string
}

export default function ProjectImportProgress({
  importId,
  onComplete
}: ProjectImportProgressProps) {
  const [status, setStatus] = useState<ImportStatus>({
    type: 'status',
    status: 'pending',
    message: 'Initializing...',
    progress: 0
  })
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    let eventSource: EventSource | null = null
    
    const connectToSSE = () => {
      // Close any existing connection
      if (eventSource) {
        eventSource.close()
      }
      
      // Create a new connection
      eventSource = new EventSource(`/api/projects/import/status/${importId}`)
      
      // Connection opened
      eventSource.onopen = () => {
        setConnected(true)
      }
      
      // Connection error
      eventSource.onerror = (error) => {
        console.error('SSE connection error:', error)
        setConnected(false)
        
        // Attempt to reconnect after 2 seconds
        setTimeout(connectToSSE, 2000)
      }
      
      // Message received
      eventSource.onmessage = (event) => {
        try {
          const data: ImportStatus = JSON.parse(event.data)
          setStatus(data)
          
          // If import is complete, close the connection and notify parent
          if (data.status === 'completed') {
            eventSource?.close()
            onComplete()
          }
          
          // If import failed, close the connection
          if (data.status === 'failed') {
            eventSource?.close()
          }
        } catch (error) {
          console.error('Error parsing SSE message:', error)
        }
      }
    }
    
    // Start connection
    connectToSSE()
    
    // Cleanup on unmount
    return () => {
      if (eventSource) {
        eventSource.close()
      }
    }
  }, [importId, onComplete])

  // Define the steps in the import process
  const steps = [
    { label: 'Clone Repository', progress: 20 },
    { label: 'Validate Project', progress: 50 },
    { label: 'Import & Process', progress: 80 },
    { label: 'Complete Analysis', progress: 100 }
  ]
  
  // Determine current step based on progress
  const currentStep = steps.findIndex(step => status.progress < step.progress)
  const activeStep = currentStep === -1 ? steps.length - 1 : currentStep - 1
  
  return (
    <div className="py-2">
      <div className="mb-6">
        <div className="flex justify-between mb-2">
          <span className="text-sm font-medium">{status.message}</span>
          <span className="text-sm font-medium">{status.progress}%</span>
        </div>
        
        {/* Progress bar */}
        <div className="w-full bg-gray-200 rounded-full h-2.5">
          <div 
            className={`h-2.5 rounded-full ${
              status.status === 'failed' 
                ? 'bg-red-600' 
                : status.status === 'completed' 
                  ? 'bg-green-600' 
                  : 'bg-blue-600'
            }`}
            style={{ width: `${status.progress}%` }}
          ></div>
        </div>
      </div>
      
      {/* Steps indicator */}
      <div className="space-y-4">
        {steps.map((step, index) => (
          <div key={index} className="flex items-start">
            <div className="flex-shrink-0 w-8 h-8 flex items-center justify-center">
              {index < activeStep ? (
                // Completed step
                <div className="w-6 h-6 bg-green-500 rounded-full flex items-center justify-center text-white">
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20 6 9 17 4 12"></polyline>
                  </svg>
                </div>
              ) : index === activeStep ? (
                // Current step
                <div className="w-6 h-6 bg-blue-500 rounded-full flex items-center justify-center">
                  <Spinner size="sm" className="text-white" />
                </div>
              ) : (
                // Future step
                <div className="w-6 h-6 bg-gray-200 rounded-full flex items-center justify-center text-gray-500">
                  <span className="text-xs font-medium">{index + 1}</span>
                </div>
              )}
            </div>
            <div className="ml-4 min-w-0 flex-1">
              <p className={`text-sm font-medium ${
                index < activeStep 
                  ? 'text-green-500' 
                  : index === activeStep 
                    ? 'text-blue-500' 
                    : 'text-gray-500'
              }`}>
                {step.label}
              </p>
            </div>
          </div>
        ))}
      </div>
      
      {/* Error message */}
      {status.status === 'failed' && status.error && (
        <div className="mt-4 p-3 bg-red-50 text-red-800 rounded border border-red-200">
          <h4 className="text-sm font-semibold mb-1">Import Failed</h4>
          <p className="text-sm">{status.error}</p>
        </div>
      )}
      
      {/* Connection status indicator */}
      <div className="mt-4 flex items-center">
        <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-500' : 'bg-red-500'} mr-2`}></div>
        <span className="text-xs text-gray-500">
          {connected ? 'Connected to server' : 'Connecting...'}
        </span>
      </div>
    </div>
  )
}