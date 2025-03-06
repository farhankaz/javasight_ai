'use client';

import { Navbar } from './components/Navbar'
import { ProjectCard } from './components/ProjectCard'
import { useEffect, useState } from 'react'

interface Project {
  _id: string;
  projectName: string;
  analysis?: string;
  projectLocation: string;
  moduleCount: number;
  packageCount: number;
  fileCount: number;
  linesOfCode: number;
}

async function getProjects() {
  // Get auth token from environment variable
  const authToken = process.env.NEXT_PUBLIC_JAVASIGHT_API_KEY
  if (!authToken) {
    throw new Error('JAVASIGHT_API_KEY environment variable is not set')
  }

  const apiUrl = process.env.NEXT_PUBLIC_API_URL
  if (!apiUrl) {
    throw new Error('NEXT_PUBLIC_API_URL environment variable is not set')
  }

  const res = await fetch(`${apiUrl}/api/projects`, { 
    cache: 'no-store',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${authToken}`
    },
  })
  
  if (!res.ok) {
    const errorText = await res.text()
    let errorMessage = `Failed to fetch projects: ${res.status} ${res.statusText}`
    
    try {
      const errorData = JSON.parse(errorText)
      errorMessage = errorData.error || errorMessage
    } catch {
      if (errorText) {
        errorMessage = errorText
      }
    }
    throw new Error(errorMessage)
  }
  
  return res.json()
}

export default function Home() {
  const [projects, setProjects] = useState<Project[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchProjects = async () => {
      try {
        const data = await getProjects()
        setProjects(data)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to fetch projects')
      }
    }
    fetchProjects()
  }, [])
  
  if (error) {
    return (
      <div className="min-h-screen bg-gray-50">
        <Navbar />
        <main className="mx-auto max-w-7xl px-4 py-8">
          <div className="text-red-600">Error: {error}</div>
        </main>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Navbar />
      <main className="mx-auto max-w-7xl px-4 py-8">
        <h1 className="mb-8 text-3xl font-bold text-gray-900">Java Projects Analysis</h1>
        
        <div className="grid gap-6 sm:grid-cols-1 lg:grid-cols-2">
          {projects.map((project) => (
            <ProjectCard 
              key={project._id}
              id={project._id}
              title={project.projectName}
              description={project.analysis || 'No analysis available'}
              modules={project.moduleCount}
              packages={project.packageCount}
              files={project.fileCount}
              linesOfCode={project.linesOfCode}
            />
          ))}
        </div>
      </main>
    </div>
  )
} 