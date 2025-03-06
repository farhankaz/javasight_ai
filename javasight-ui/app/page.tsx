'use client';

import { Navbar } from './components/Navbar'
import { ProjectCard } from './components/ProjectCard'
import ImportGithubProjectModal from './components/ImportGithubProjectModal'
import { useEffect, useState, useCallback } from 'react'

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
  const [showImportModal, setShowImportModal] = useState(false)

  const fetchProjects = useCallback(async () => {
    try {
      const data = await getProjects()
      setProjects(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch projects')
    }
  }, [])

  useEffect(() => {
    fetchProjects()
  }, [fetchProjects])
  
  const handleImportSuccess = useCallback(() => {
    // Refresh the projects list after successful import
    fetchProjects()
  }, [fetchProjects])

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
        <div className="flex justify-between items-center mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Java Projects</h1>
          <button 
            onClick={() => setShowImportModal(true)}
            className="inline-flex items-center px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
            </svg>
            Add Project
          </button>
        </div>
        
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

        <ImportGithubProjectModal
          isOpen={showImportModal}
          onClose={() => setShowImportModal(false)}
          onSuccess={handleImportSuccess}
        />
      </main>
    </div>
  )
}